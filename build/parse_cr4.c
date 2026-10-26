/* 精简解析器：直接调用 libdivecomputer 的 deepsix_excursion 解析器
 * （= Crest CR-4 / Deep Six Excursion / Genesis Centauri / Scorpena Alpha）
 * 不依赖 autotools / libusb / bluez，只用 MinGW gcc 编这一小撮源文件。
 *
 * 用法: parse_cr4 <dive.bin> [...]
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libdivecomputer/context.h>
#include <libdivecomputer/parser.h>

#include "deepsix_excursion.h"

static unsigned int g_time = 0;
static double g_depth = 0.0;
static double g_temp = 0.0;
static int g_have_temp = 0;
static unsigned int g_rows = 0;

static void sample_cb(dc_sample_type_t type, const dc_sample_value_t *value, void *userdata)
{
	(void) userdata;
	switch (type) {
	case DC_SAMPLE_TIME:
		g_time = value->time;
		break;
	case DC_SAMPLE_DEPTH:
		g_depth = value->depth;
		if (g_have_temp)
			printf("%u,%.2f,%.1f\n", g_time / 1000, g_depth, g_temp);
		else
			printf("%u,%.2f\n", g_time / 1000, g_depth);
		g_rows++;
		break;
	case DC_SAMPLE_TEMPERATURE:
		g_temp = value->temperature;
		g_have_temp = 1;
		break;
	case DC_SAMPLE_EVENT:
		printf("# event: type=%u flags=%u value=%u at t=%u\n",
		       value->event.type, value->event.flags, value->event.value, g_time / 1000);
		break;
	case DC_SAMPLE_GASMIX:
		printf("# gasmix: %u\n", value->gasmix);
		break;
	default:
		break;
	}
}

static int parse_one(const char *path)
{
	FILE *f = fopen(path, "rb");
	if (!f) { fprintf(stderr, "打不开 %s\n", path); return 1; }
	fseek(f, 0, SEEK_END);
	long size = ftell(f);
	fseek(f, 0, SEEK_SET);
	unsigned char *data = (unsigned char *) malloc((size_t) size);
	if (!data) { fclose(f); return 1; }
	if (fread(data, 1, (size_t) size, f) != (size_t) size) {
		fprintf(stderr, "读取失败: %s\n", path);
		fclose(f); free(data); return 1;
	}
	fclose(f);

	dc_context_t *ctx = NULL;
	if (dc_context_new(&ctx) != DC_STATUS_SUCCESS || ctx == NULL) {
		fprintf(stderr, "创建 context 失败\n");
		free(data); return 1;
	}
	dc_parser_t *parser = NULL;

	dc_status_t rc = deepsix_excursion_parser_create(&parser, ctx, data, (size_t) size);
	if (rc != DC_STATUS_SUCCESS) {
		fprintf(stderr, "创建解析器失败: %d\n", rc);
		dc_context_free(ctx); free(data); return 1;
	}

	printf("=== %s (%ld 字节) ===\n", path, size);

	dc_datetime_t dt;
	memset(&dt, 0, sizeof(dt));
	if (dc_parser_get_datetime(parser, &dt) == DC_STATUS_SUCCESS)
		printf("datetime   : %04d-%02d-%02d %02d:%02d:%02d\n",
		       dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);

	unsigned int divetime = 0;
	if (dc_parser_get_field(parser, DC_FIELD_DIVETIME, 0, &divetime) == DC_STATUS_SUCCESS)
		printf("divetime   : %u s (%u min)\n", divetime, divetime / 60);

	double maxdepth = 0.0;
	if (dc_parser_get_field(parser, DC_FIELD_MAXDEPTH, 0, &maxdepth) == DC_STATUS_SUCCESS)
		printf("maxdepth   : %.2f m\n", maxdepth);

	double tmin = 0.0;
	if (dc_parser_get_field(parser, DC_FIELD_TEMPERATURE_MINIMUM, 0, &tmin) == DC_STATUS_SUCCESS)
		printf("temp_min   : %.1f C\n", tmin);

	unsigned int ngas = 0;
	if (dc_parser_get_field(parser, DC_FIELD_GASMIX_COUNT, 0, &ngas) == DC_STATUS_SUCCESS)
		printf("gasmixes   : %u\n", ngas);

	printf("--- samples (t_sec,depth_m,temp_c) ---\n");
	g_time = 0; g_depth = 0.0; g_rows = 0;
	rc = dc_parser_samples_foreach(parser, sample_cb, NULL);
	printf("--- 共 %u 个深度采样点, rc=%d ---\n", g_rows, rc);

	dc_parser_destroy(parser);
	dc_context_free(ctx);
	free(data);
	return 0;
}

int main(int argc, char **argv)
{
	if (argc < 2) {
		fprintf(stderr, "用法: %s <dive.bin> [...]\n", argv[0]);
		return 2;
	}
	for (int i = 1; i < argc; ++i)
		parse_one(argv[i]);
	return 0;
}
