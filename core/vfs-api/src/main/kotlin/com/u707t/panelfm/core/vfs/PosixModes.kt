package com.u707t.panelfm.core.vfs

import java.nio.file.attribute.PosixFilePermission

/** POSIX mode ↔ PosixFilePermission 互转（本地 chmod / SFTP setstat 共用）。 */
object PosixModes {

    fun toMode(perms: Set<PosixFilePermission>?): Int? {
        if (perms == null) return null
        var mode = 0
        perms.forEach {
            mode = mode or when (it) {
                PosixFilePermission.OWNER_READ -> 0b100_000_000
                PosixFilePermission.OWNER_WRITE -> 0b010_000_000
                PosixFilePermission.OWNER_EXECUTE -> 0b001_000_000
                PosixFilePermission.GROUP_READ -> 0b000_100_000
                PosixFilePermission.GROUP_WRITE -> 0b000_010_000
                PosixFilePermission.GROUP_EXECUTE -> 0b000_001_000
                PosixFilePermission.OTHERS_READ -> 0b000_000_100
                PosixFilePermission.OTHERS_WRITE -> 0b000_000_010
                PosixFilePermission.OTHERS_EXECUTE -> 0b000_000_001
            }
        }
        return mode
    }

    fun toPermissions(mode: Int): Set<PosixFilePermission> {
        val out = mutableSetOf<PosixFilePermission>()
        if (mode and 0b100_000_000 != 0) out += PosixFilePermission.OWNER_READ
        if (mode and 0b010_000_000 != 0) out += PosixFilePermission.OWNER_WRITE
        if (mode and 0b001_000_000 != 0) out += PosixFilePermission.OWNER_EXECUTE
        if (mode and 0b000_100_000 != 0) out += PosixFilePermission.GROUP_READ
        if (mode and 0b000_010_000 != 0) out += PosixFilePermission.GROUP_WRITE
        if (mode and 0b000_001_000 != 0) out += PosixFilePermission.GROUP_EXECUTE
        if (mode and 0b000_000_100 != 0) out += PosixFilePermission.OTHERS_READ
        if (mode and 0b000_000_010 != 0) out += PosixFilePermission.OTHERS_WRITE
        if (mode and 0b000_000_001 != 0) out += PosixFilePermission.OTHERS_EXECUTE
        return out
    }
}
