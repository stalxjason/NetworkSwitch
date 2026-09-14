package io.github.stalxjason.networkswitch

import org.junit.Assert.*
import org.junit.Test

class ShizukuHelperTest {

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
    fun `Status sealed class has exactly four states`() {
        // 新增状态而不补 rank 分支时，下面的 when 编译不过，从而强制同步调用方
        val ranks = listOf(
            rank(ShizukuHelper.Status.Authorized),
            rank(ShizukuHelper.Status.Running),
            rank(ShizukuHelper.Status.NotRunning),
            rank(ShizukuHelper.Status.NotInstalled)
        )
        assertEquals(4, ranks.distinct().size)
        assertTrue(ranks.contains(0))
        assertTrue(ranks.contains(3))
    }

    private fun rank(status: ShizukuHelper.Status): Int = when (status) {
        is ShizukuHelper.Status.Authorized -> 3
        is ShizukuHelper.Status.Running -> 2
        is ShizukuHelper.Status.NotRunning -> 1
        is ShizukuHelper.Status.NotInstalled -> 0
    }
}
