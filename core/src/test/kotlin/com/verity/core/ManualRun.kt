package com.verity.core

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Dev tool: `gradle :core:test --tests '*ManualRun*' -Pverity.input=/path/photo.jpg` grades a real photo. */
class ManualRun {
    @Test fun gradeRealPhoto() {
        val path = System.getProperty("verity.input") ?: return
        val bi = ImageIO.read(File(path))
        val img = ArgbImage(bi.width, bi.height).also { bi.getRGB(0, 0, bi.width, bi.height, it.pixels, 0, bi.width) }
        val faces = (System.getProperty("verity.faces") ?: "1").toInt()
        val face = System.getProperty("verity.face")?.split(",")?.map { it.trim().toFloat() }?.let { NormRect(it[0], it[1], it[2], it[3]) }
        val s = SceneAnalyzer.analyze(img, hasFaces = faces > 0, faces = listOfNotNull(face))
        val scene = AutoGrader.classify(s, faces)
        val p = AutoGrader.propose(s, scene)
        println("stats p01=%.4f p05=%.4f p50=%.4f p95=%.4f p99=%.4f gains=%s cast=%.2f clip=%.3f skin=%.2f noise=%.4f dr=%.1f face=%.3f".format(
            s.p01, s.p05, s.p50, s.p95, s.p99, s.neutralGains.joinToString(), s.castStrength, s.clippedFraction, s.skinFraction, s.noiseSigma, s.dynamicRangeStops, s.faceLuma))
        println("scene=$scene params=$p")
        val out = Renderer.render(img, s, p, RenderOptions(targetLongEdge = 0, subjects = listOfNotNull(face)))
        val side = BufferedImage(img.width * 2 + 8, img.height, BufferedImage.TYPE_INT_RGB)
        side.setRGB(0, 0, img.width, img.height, img.pixels, 0, img.width)
        side.setRGB(img.width + 8, 0, out.width, out.height, out.pixels, 0, out.width)
        ImageIO.write(side, "png", File(System.getProperty("verity.output") ?: "build/visual/manual.png"))
    }
}
