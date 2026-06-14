package com.github.jcnlk.skijarenderer.skia

data class SkijaFont(val location: String) {
    val bytes: ByteArray by lazy { ResourceLoader.read(location) }
}
