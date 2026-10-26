/* 桩函数：只为满足链接。本工具只走 deepsix_excursion 解析路径，
 * 这些符号属于其它厂牌 / 设备层，永远不会被执行到。 */
#include <stddef.h>

unsigned int dc_descriptor_get_model(const void *d) { (void) d; return 0; }
unsigned int dc_descriptor_get_type(const void *d) { (void) d; return 0; }
unsigned int dc_device_get_type(const void *d) { (void) d; return 0; }

int oceans_s1_getline(void *a, void *b, size_t c) { (void) a; (void) b; (void) c; return 0; }
int seac_screen_record_isvalid(const void *a, size_t b) { (void) a; (void) b; return 0; }
