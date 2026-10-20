package com.u707t.panelfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/proc/net/route` 网关字段解析的回归锁定。
 *
 * 这里唯一的坑是**字节序**：字段是 8 位十六进制、小端序（`0101A8C0` = 192.168.1.1）。
 * 写反不会抛异常，只会静默把 `1.1.168.192` 填进「主机 / IP」——连接失败还查不出原因。
 */
class DefaultGatewayTest {

    @Test
    fun `小端序十六进制还原成点分十进制`() {
        assertEquals("192.168.1.1", DefaultGateway.gatewayFromProcHex("0101A8C0"))
        assertEquals("10.0.0.1", DefaultGateway.gatewayFromProcHex("0100000A"))
        assertEquals("172.16.31.254", DefaultGateway.gatewayFromProcHex("FE1F10AC"))
    }

    @Test
    fun `无网关与非法字段返回 null`() {
        assertNull(DefaultGateway.gatewayFromProcHex("00000000"))
        assertNull(DefaultGateway.gatewayFromProcHex(""))
        assertNull(DefaultGateway.gatewayFromProcHex("1A8C0"))
        assertNull(DefaultGateway.gatewayFromProcHex("ZZZZZZZZ"))
    }
}
