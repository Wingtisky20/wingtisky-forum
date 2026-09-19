/**
 * 秒杀：券、库存、Lua 扣减、防刷。
 *
 * <p>边界约束：属于秒杀域，不得依赖 forum/** 与 trade/**；跨域数据通过 wt-domain 的契约拿。
 * 本模块是被调用方，不引 web starter。
 */
package com.wingtisky.forum.seckill.coupon;
