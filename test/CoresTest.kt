import dev.droidtop.plugins.retroarch.RetroArchCores

private fun check(ok: Boolean, what: String) {
    if (!ok) throw AssertionError(what)
    println("ok: $what")
}

private fun elf(is64: Boolean, machine: Int, shoff: Long, shentsize: Int, shnum: Int): ByteArray {
    val h = ByteArray(if (is64) 64 else 52)
    h[0] = 0x7f; h[1] = 'E'.code.toByte(); h[2] = 'L'.code.toByte(); h[3] = 'F'.code.toByte()
    h[4] = if (is64) 2 else 1
    h[5] = 1
    h[18] = (machine and 0xff).toByte(); h[19] = (machine shr 8).toByte()
    fun put(at: Int, v: Long, n: Int) { for (i in 0 until n) h[at + i] = ((v shr (8 * i)) and 0xff).toByte() }
    if (is64) { put(40, shoff, 8); put(58, shentsize.toLong(), 2); put(60, shnum.toLong(), 2) }
    else { put(32, shoff, 4); put(46, shentsize.toLong(), 2); put(48, shnum.toLong(), 2) }
    return h
}

fun main() {
    val arm64Device = listOf("arm64-v8a", "armeabi-v7a")
    check(RetroArchCores.abiFor("com.retroarch.aarch64", arm64Device) == "arm64-v8a", "aarch64 build takes the 64-bit core")
    check(RetroArchCores.abiFor("com.retroarch.ra32", arm64Device) == "armeabi-v7a", "ra32 build on an arm64 device takes the 32-bit core, not the device's first ABI")
    check(RetroArchCores.abiFor("com.retroarch", arm64Device) == "arm64-v8a", "the universal build takes the device's preference")
    check(RetroArchCores.abiFor("com.retroarch.aarch64", listOf("armeabi-v7a")) == null, "an aarch64 build on a 32-bit-only device has no ABI")

    val good = elf(true, 183, 1000, 64, 10)
    check(RetroArchCores.isWholeElf(good, 1640, "arm64-v8a"), "a whole arm64 ELF is accepted")
    check(!RetroArchCores.isWholeElf(good, 1639, "arm64-v8a"), "a file cut inside its section headers is refused")
    check(!RetroArchCores.isWholeElf(good, 1640, "armeabi-v7a"), "an arm64 ELF is not an armeabi-v7a core")
    check(RetroArchCores.isWholeElf(elf(false, 40, 500, 40, 5), 700, "armeabi-v7a"), "a whole 32-bit ARM ELF is accepted")
    check(!RetroArchCores.isWholeElf(ByteArray(64), 4096, "arm64-v8a"), "a file without the ELF magic is refused")
    check(!RetroArchCores.isWholeElf(ByteArray(8), 8, "arm64-v8a"), "a short file is refused")
}
