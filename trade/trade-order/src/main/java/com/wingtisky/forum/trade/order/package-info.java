/**
 * 订单：下单、状态机、对账。
 *
 * <p>边界约束：属于交易域，不得依赖 forum/** 与 seckill/**；跨域数据通过 wt-domain 的契约拿
 * （订单归属的用户信息即由 wt-domain 接口提供，不直接依赖 forum-user）。
 */
package com.wingtisky.forum.trade.order;
