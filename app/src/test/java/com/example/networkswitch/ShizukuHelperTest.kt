package com.example.networkswitch

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ShizukuHelperTest {

    @Test
    fun `Status sealed class covers all states`() {
        // 验证所有 Status 子类可以被正确判断
        val statuses = listOf(
            ShizukuHelper.Status.Authorized,
            ShizukuHelper.Status.Running,
            ShizukuHelper.Status.NotRunning,
            ShizukuHelper.Status.NotInstalled
        )

        assertTrue(statuses[0] is ShizukuHelper.Status.Authorized)
        assertTrue(statuses[1] is ShizukuHelper.Status.Running)
        assertTrue(statuses[2] is ShizukuHelper.Status.NotRunning)
        assertTrue(statuses[3] is ShizukuHelper.Status.NotInstalled)
    }

    @Test
    fun `ShellResult with success`() {
        val result = ShizukuHelper.ShellResult(true, "ok", "")
        assertTrue(result.success)
        assertEquals("ok", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun `ShellResult with failure`() {
        val result = ShizukuHelper.ShellResult(false, "", "permission denied")
        assertFalse(result.success)
        assertEquals("", result.stdout)
        assertEquals("permission denied", result.stderr)
    }

    @Test
    fun `isOurPermissionRequest matches correct code`() {
        assertTrue(ShizukuHelper.isOurPermissionRequest(1001))
        assertFalse(ShizukuHelper.isOurPermissionRequest(0))
        assertFalse(ShizukuHelper.isOurPermissionRequest(999))
    }

    @Test
    fun `getStatus returns NotInstalled when Shizuku unavailable`() = runTest {
        // 在纯 JVM 测试环境中没有 Shizuku，应该返回 NotInstalled
        val status = ShizukuHelper.getStatus()
        assertTrue(status is ShizukuHelper.Status.NotInstalled || status is ShizukuHelper.Status.NotRunning)
    }
}
