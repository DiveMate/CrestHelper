/* JNI 桥接：把 libdivecomputer 的 deepsix_excursion 解析能力暴露给 Kotlin。
 *
 * 设计取舍：BLE 扫描/连接/下载由 Kotlin 侧的 BluetoothGatt 负责，
 * 本 .so 只负责「原始字节 -> 结构化 dive 数据」的解析（对应 parse_cr4 的能力），
 * 因此不编设备层/传输层（descriptor/usb/ble/bluetooth…），大幅简化交叉编译。
 *
 * 输出用 JSON 字符串（手工拼接），Kotlin 侧用 org.json 解析。
 * 气体（FO2/He）与气体切换只有遍历采样时才会被解析器发现，所以 gas 字段
 * 必须在 samples_foreach 之后读取 —— 顺序错了就永远是空的。
 */
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

extern "C" {
#include <libdivecomputer/context.h>
#include <libdivecomputer/parser.h>
#include "deepsix_excursion.h"
}

#define TAG "DCBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ---- 可增长字符串缓冲 ---- */
typedef struct { char *buf; size_t len, cap; } sb_t;

static void sb_init(sb_t *sb) {
    sb->cap = 8192; sb->len = 0;
    sb->buf = (char *) malloc(sb->cap);
    if (sb->buf) sb->buf[0] = 0;
}
static void sb_add(sb_t *sb, const char *s) {
    size_t n = strlen(s);
    if (!sb->buf) return;
    if (sb->len + n + 1 > sb->cap) {
        size_t ncap = sb->cap;
        while (ncap < sb->len + n + 1) ncap *= 2;
        char *nb = (char *) realloc(sb->buf, ncap);
        if (!nb) return;
        sb->buf = nb; sb->cap = ncap;
    }
    memcpy(sb->buf + sb->len, s, n + 1);
    sb->len += n;
}
static void sb_free(sb_t *sb) { if (sb->buf) free(sb->buf); sb->buf = NULL; sb->len = sb->cap = 0; }

/* ---- 采样点累加器 ---- */
typedef struct {
    sb_t sb;
    int first;
    unsigned int cur_time;   /* 毫秒 */
    double cur_depth;
    double cur_temp;
    int have_temp;
    unsigned int count;
    /* 气体：mix 索引由 DC_SAMPLE_GASMIX 事件推进；ngas==0 表示这份记录没带气体信息
     *（CR-4 的 v0 记录格式就是这样，实测 294 份样本全部如此）。 */
    unsigned int cur_mix;
    int have_mix;
} samples_t;

static void sample_cb(dc_sample_type_t type, const dc_sample_value_t *value, void *userdata) {
    samples_t *acc = (samples_t *) userdata;
    char tmp[200];
    switch (type) {
    case DC_SAMPLE_TIME:
        acc->cur_time = value->time;
        break;
    case DC_SAMPLE_DEPTH:
        acc->cur_depth = value->depth;
        sb_add(&acc->sb, acc->first ? "[" : ",");
        acc->first = 0;
        if (acc->have_temp && acc->have_mix)
            snprintf(tmp, sizeof tmp, "{\"t\":%u,\"depth\":%.3f,\"temp\":%.2f,\"g\":%u}",
                     acc->cur_time / 1000, acc->cur_depth, acc->cur_temp, acc->cur_mix);
        else if (acc->have_temp)
            snprintf(tmp, sizeof tmp, "{\"t\":%u,\"depth\":%.3f,\"temp\":%.2f}",
                     acc->cur_time / 1000, acc->cur_depth, acc->cur_temp);
        else if (acc->have_mix)
            snprintf(tmp, sizeof tmp, "{\"t\":%u,\"depth\":%.3f,\"g\":%u}",
                     acc->cur_time / 1000, acc->cur_depth, acc->cur_mix);
        else
            snprintf(tmp, sizeof tmp, "{\"t\":%u,\"depth\":%.3f}",
                     acc->cur_time / 1000, acc->cur_depth);
        sb_add(&acc->sb, tmp);
        acc->count++;
        break;
    case DC_SAMPLE_TEMPERATURE:
        acc->cur_temp = value->temperature;
        acc->have_temp = 1;
        break;
    case DC_SAMPLE_GASMIX:
        acc->cur_mix = value->gasmix;
        acc->have_mix = 1;
        break;
    default:
        break;
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_divelog_app_DiveParser_nativeVersion(JNIEnv *env, jobject /*thiz*/) {
    LOGI("nativeVersion called");
    return env->NewStringUTF("dcbridge 1.0 (deepsix_excursion parser, LGPL libdivecomputer)");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_divelog_app_DiveParser_nativeParseDive(JNIEnv *env, jobject /*thiz*/, jbyteArray data) {
    if (data == nullptr) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"null data\"}");
    }

    jsize len = env->GetArrayLength(data);
    if (len <= 0) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"empty data\"}");
    }
    /* 真实 CR-4 记录最小 198 字节（实测 294 条）。低于 64 字节必是垃圾/截断，
     * 直接判无效，避免 libdc 对 1 字节输入"解析成功"返回一条全零空记录，
     * 污染上层 UI 与统计。 */
    if (len < 64) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"data too short\"}");
    }

    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"GetByteArrayElements failed\"}");
    }
    /* 拷贝一份，GetByteArrayElements 之后还会 Release */
    unsigned char *buf = (unsigned char *) malloc((size_t) len);
    if (buf == nullptr) {
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
        return env->NewStringUTF("{\"ok\":false,\"error\":\"oom\"}");
    }
    memcpy(buf, bytes, (size_t) len);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    LOGI("parseDive: %d bytes", len);

    dc_context_t *ctx = nullptr;
    if (dc_context_new(&ctx) != DC_STATUS_SUCCESS || ctx == nullptr) {
        free(buf);
        return env->NewStringUTF("{\"ok\":false,\"error\":\"dc_context_new failed\"}");
    }

    dc_parser_t *parser = nullptr;
    dc_status_t rc = deepsix_excursion_parser_create(&parser, ctx, buf, (size_t) len);
    if (rc != DC_STATUS_SUCCESS) {
        LOGE("parser_create failed rc=%d", rc);
        dc_context_free(ctx);
        free(buf);
        char err[96];
        snprintf(err, sizeof err, "{\"ok\":false,\"error\":\"parser_create rc=%d\"}", rc);
        return env->NewStringUTF(err);
    }

    /* 元信息 */
    unsigned int divetime = 0;
    dc_parser_get_field(parser, DC_FIELD_DIVETIME, 0, &divetime);
    double maxdepth = 0.0;
    dc_parser_get_field(parser, DC_FIELD_MAXDEPTH, 0, &maxdepth);
    double tmin = 0.0;
    dc_parser_get_field(parser, DC_FIELD_TEMPERATURE_MINIMUM, 0, &tmin);

    dc_datetime_t dt;
    memset(&dt, 0, sizeof dt);
    int has_dt = (dc_parser_get_datetime(parser, &dt) == DC_STATUS_SUCCESS);

    /* 采样点 */
    samples_t acc;
    sb_init(&acc.sb);
    acc.first = 1; acc.cur_time = 0; acc.cur_depth = 0; acc.cur_temp = 0;
    acc.have_temp = 0; acc.count = 0; acc.cur_mix = 0; acc.have_mix = 0;
    dc_parser_samples_foreach(parser, sample_cb, &acc);
    if (acc.first) sb_add(&acc.sb, "[]");           /* 空数组 */
    else sb_add(&acc.sb, "]");

    /* 气体表必须**在采样遍历之后**读：deepsix 解析器是在遍历过程中
     * 遇到气体切换事件时才把 mix 填进 parser->gasmix[] 的（见
     * deepsix_excursion_parser.c 的 EVENT_CHANGE_GAS 分支）。
     * 之前在这里提前读 gasmixCount，永远得到 0。 */
    unsigned int ngas = 0;
    dc_parser_get_field(parser, DC_FIELD_GASMIX_COUNT, 0, &ngas);

    sb_t gas;
    sb_init(&gas);
    sb_add(&gas, "[");
    for (unsigned int i = 0; i < ngas; i++) {
        dc_gasmix_t g;
        memset(&g, 0, sizeof g);
        if (dc_parser_get_field(parser, DC_FIELD_GASMIX, i, &g) != DC_STATUS_SUCCESS) continue;
        char tmp[96];
        snprintf(tmp, sizeof tmp, "%s{\"o2\":%.4f,\"he\":%.4f}",
                 i ? "," : "", g.oxygen, g.helium);
        sb_add(&gas, tmp);
    }
    sb_add(&gas, "]");

    /* 又一个防御：全零/纯垃圾数据可能让 libdc "解析成功" 但一条采样都没有、
     * 时长也是 0。这种空记录对上层毫无意义（后续过滤规则也会丢弃），
     * 这里直接判无效，避免脏数据混进日志列表。 */
    if (acc.count == 0 && divetime == 0) {
        LOGE("parseDive: no usable data (samples=0, divetime=0) -> invalid");
        sb_free(&acc.sb);
        sb_free(&gas);
        dc_parser_destroy(parser);
        dc_context_free(ctx);
        free(buf);
        return env->NewStringUTF("{\"ok\":false,\"error\":\"no usable data\"}");
    }

    /* 组装 JSON */
    sb_t out;
    sb_init(&out);
    char head[640];
    snprintf(head, sizeof head,
             "{\"ok\":true,\"bytes\":%d,\"divetime\":%u,\"maxdepth\":%.3f,"
             "\"tempMin\":%.2f,\"gasmixCount\":%u,\"sampleCount\":%u,"
             "\"datetime\":",
             len, divetime, maxdepth, tmin, ngas, acc.count);
    sb_add(&out, head);

    if (has_dt) {
        char dts[128];
        snprintf(dts, sizeof dts,
                 "{\"year\":%d,\"month\":%d,\"day\":%d,\"hour\":%d,\"minute\":%d,\"second\":%d}",
                 dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);
        sb_add(&out, dts);
    } else {
        sb_add(&out, "null");
    }

    sb_add(&out, ",\"gas\":");
    sb_add(&out, gas.buf ? gas.buf : "[]");

    sb_add(&out, ",\"samples\":");
    if (out.buf && acc.sb.buf) sb_add(&out, acc.sb.buf);
    else sb_add(&out, "[]");
    sb_add(&out, "}");

    LOGI("parseDive: ok, %u samples, divetime=%u, maxdepth=%.2f, ngas=%u",
         acc.count, divetime, maxdepth, ngas);

    jstring result = env->NewStringUTF(out.buf ? out.buf : "{\"ok\":false,\"error\":\"build failed\"}");

    sb_free(&out);
    sb_free(&gas);
    sb_free(&acc.sb);
    dc_parser_destroy(parser);
    dc_context_free(ctx);
    free(buf);
    return result;
}
