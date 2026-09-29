/**
 * 契约模块：领域模型 + 跨域接口（只放契约，不放实现）。
 *
 * <p>边界约束：三个业务域两两不得互相依赖，只能通过本模块的接口通信；
 * 本模块不依赖任何业务域（forum/**、trade/**、seckill/**）。
 */
package com.wingtisky.forum.domain;
