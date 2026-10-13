package com.nonxedy.playback

import com.nonxedy.model.playback.FadeMode
import com.nonxedy.model.playback.FadeSettings
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.time.Duration

// Plays one fade-to-black-and-back envelope. Must be called on the main thread.
object ScreenFade {

    fun cover(player: Player, fade: FadeSettings) {
        when (fade.mode) {
            FadeMode.NONE -> Unit
            FadeMode.BLINDNESS -> {
                player.addPotionEffect(
                    PotionEffect(PotionEffectType.BLINDNESS, fade.totalTicks, 0, false, false, false)
                )
            }
            FadeMode.TITLE -> {
                val times = Title.Times.times(
                    ticks(fade.outTicks),
                    ticks(fade.holdTicks),
                    ticks(fade.inTicks)
                )
                player.showTitle(
                    Title.title(MiniMessage.miniMessage().deserialize(fade.titleGlyph), Component.empty(), times)
                )
            }
        }
    }

    private fun ticks(value: Int): Duration = Duration.ofMillis(value * 50L)
}
