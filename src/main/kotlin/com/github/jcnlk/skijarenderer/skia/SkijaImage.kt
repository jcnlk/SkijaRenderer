package com.github.jcnlk.skijarenderer.skia

data class SkijaImage(
    val location: String,
    val isSvg: Boolean = location.endsWith(".svg", ignoreCase = true),
) {
    val bytes: ByteArray by lazy { ResourceLoader.read(location) }
}
