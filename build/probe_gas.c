/* 临时探针：把手表原始日志里「能读到什么元信息」全打出来。
 * 关注：气体（FO2/He）、气体切换事件、CCR setpoint、GF、盐度、模式。
 *
 * 用法: probe_gas <dive.bin> [...]
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libdivecomputer/context.h>
#include <libdivecomputer/parser.h>

#include "deepsix_excursion.h"

typedef struct {
	int gaschg;
	int setpoint;
	int deco;
	unsigned int rows;
} acc_t;

static void sample_cb(dc_sample_type_t type, const dc_sample_value_t *value, void *userdata)
{
	acc_t *a = (acc_t *) userdata;
	switch (type) {
	case DC_SAMPLE_DEPTH:
		a->rows++;
		break;
	case DC_SAMPLE_GASMIX:
		a->gaschg++;
		break;
	case DC_SAMPLE_SETPOINT:
		a->setpoint++;
		break;
	case DC_SAMPLE_DECO:
		a->deco++;
		break;
	default:
		break;
	}
}

static int parse_one(const char *path)
{
	FILE *f = fopen(path, "rb");
	if (!f) { fprintf(stderr, "open fail %s\n", path); return 1; }
	fseek(f, 0, SEEK_END);
	long size = ftell(f);
	fseek(f, 0, SEEK_SET);
	unsigned char *data = (unsigned char *) malloc((size_t) size);
	if (fread(data, 1, (size_t) size, f) != (size_t) size) { fclose(f); free(data); return 1; }
	fclose(f);

	dc_context_t *ctx = NULL;
	dc_context_new(&ctx);
	dc_parser_t *parser = NULL;
	dc_status_t rc = deepsix_excursion_parser_create(&parser, ctx, data, (size_t) size);
	if (rc != DC_STATUS_SUCCESS) {
		printf("%s\tERR create=%d\n", path, rc);
		dc_context_free(ctx); free(data); return 1;
	}

	acc_t a; memset(&a, 0, sizeof a);
	dc_parser_samples_foreach(parser, sample_cb, &a);   /* gasmix 靠这遍填充 */

	unsigned int divetime = 0, ngas = 0;
	int rdt = dc_parser_get_field(parser, DC_FIELD_DIVETIME, 0, &divetime);
	int rng = dc_parser_get_field(parser, DC_FIELD_GASMIX_COUNT, 0, &ngas);

	char gas[160] = "-";
	if (ngas > 0) {
		gas[0] = 0;
		for (unsigned int i = 0; i < ngas && i < 6; i++) {
			dc_gasmix_t g; memset(&g, 0, sizeof g);
			if (dc_parser_get_field(parser, DC_FIELD_GASMIX, i, &g) != DC_STATUS_SUCCESS) continue;
			sprintf(gas + strlen(gas), "%s%.0f/%.0f", i ? ";" : "", g.oxygen * 100.0, g.helium * 100.0);
		}
		if (!gas[0]) strcpy(gas, "-");
	}

	dc_decomodel_t dm; memset(&dm, 0, sizeof dm);
	int rgf = dc_parser_get_field(parser, DC_FIELD_DECOMODEL, 0, &dm);
	dc_salinity_t sal; memset(&sal, 0, sizeof sal);
	int rsal = dc_parser_get_field(parser, DC_FIELD_SALINITY, 0, &sal);
	dc_divemode_t mode = 0;
	int rmode = dc_parser_get_field(parser, DC_FIELD_DIVEMODE, 0, &mode);
	double avgd = 0;
	int ravg = dc_parser_get_field(parser, DC_FIELD_AVGDEPTH, 0, &avgd);

	printf("%s | ver=%u rc(dt=%d,ng=%d,gf=%d,sal=%d,mode=%d,avg=%d) dt=%u rows=%u "
	       "ngas=%u gas=%s gaschg=%d sp=%d deco=%d gf=%d/%d sal=%d/%u mode=%d avg=%.2f\n",
	       path, data[3], rdt, rng, rgf, rsal, rmode, ravg, divetime, a.rows,
	       ngas, gas, a.gaschg, a.setpoint, a.deco,
	       dm.params.gf.low, dm.params.gf.high, (int) sal.type, sal.density, (int) mode, avgd);

	dc_parser_destroy(parser);
	dc_context_free(ctx);
	free(data);
	return 0;
}

int main(int argc, char **argv)
{
	for (int i = 1; i < argc; i++) parse_one(argv[i]);
	return 0;
}
