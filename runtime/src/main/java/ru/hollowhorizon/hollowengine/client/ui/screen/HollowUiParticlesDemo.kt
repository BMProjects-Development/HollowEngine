package ru.hollowhorizon.hollowengine.client.ui.screen

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.particles.UiParticleAnimation
import ru.hollowhorizon.hollowengine.client.ui.particles.UiParticleEmitter
import ru.hollowhorizon.hollowengine.client.ui.particles.particleBackground
import ru.hollowhorizon.hollowengine.client.ui.particles.rememberUiParticleSystem
import ru.hollowhorizon.hollowengine.client.utils.lang

@Composable
internal fun particlesDemo() {
    val smoke = rememberUiParticleSystem(remember { UiParticleEmitter.smoke() })
    val flames = rememberUiParticleSystem(remember {
        UiParticleEmitter.smoke("minecraft:flame").copy(
            rate = 24f,
            animation = UiParticleAnimation.RANDOM_FRAME,
            spawn = { area ->
                x = area.random.nextFloat() * area.width
                y = area.height
                velocityX = (area.random.nextFloat() - 0.5f) * 14f
                velocityY = -20f - area.random.nextFloat() * 30f
                lifetime = 1f + area.random.nextFloat()
                size = 10f + area.random.nextFloat() * 8f
            },
        )
    })
    var paused by remember { mutableStateOf(false) }
    Column(modifier = Modifier.style("hollowengine:ui/styles/particles-demo.hss").scrollable(), tags = listOf("particles-demo")) {
        Text("hollowengine.gui.particles.title".lang, tags = listOf("title"))
        Text("hollowengine.gui.particles.description".lang, tags = listOf("body"))
        Row(tags = listOf("particle-previews")) {
            Box(id = "particle-smoke-preview", tags = listOf("particle-preview"), modifier = Modifier.particleBackground(smoke)) {
                Text("hollowengine.gui.particles.smoke".lang, tags = listOf("card-title"))
            }
            Box(id = "particle-flame-preview", tags = listOf("particle-preview"), modifier = Modifier.drawBehind(flames) { drawParticles(flames) }) {
                Text("hollowengine.gui.particles.flame".lang, tags = listOf("card-title"))
            }
        }
        Box(id = "particle-pause", tags = listOf("particle-control"), modifier = Modifier
            .input(hoverable = true, clickable = true)
            .onClick {
                paused = !paused
                smoke.paused = paused
                flames.paused = paused
            },
        ) {
            Text((if (paused) "hollowengine.gui.particles.resume" else "hollowengine.gui.particles.pause").lang)
        }
    }
}
