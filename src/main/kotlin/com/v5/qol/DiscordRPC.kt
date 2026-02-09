package com.v5.qol

import kotlinx.coroutines.*
import meteordevelopment.discordipc.DiscordIPC
import meteordevelopment.discordipc.RichPresence
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.time.Instant

object DiscordRPC {

    private val logger: Logger = LogManager.getLogger("V5 Discord RPC")
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val idleStates = listOf(
        "Macroing Gemstones",
        "Duping off the Auction House",
        "Completing Commissions",
        "Stuck in a Glacite Mineshaft",
        "Breaking trees in Galatea",
        "About to be spec banned",
        "Exploiting in Dungeons",
        "Hitting Vampires with steak",
        "Dropping an Alloy",
        "Flipping on the Bazaar",
        "Getting lost in the Crystal Hollows",
        "Dying to Necron",
        "Trying to get RNG drops",
        "Completing Slayers",
        "Abusing bugs",
        "Leveling up pets",
        "Wondering where their life went",
        "Falling into the void",
        "Trying to find a free dungeon carry",
        "Listening to NPCs"
    )

    private val rpc: RichPresence = RichPresence()
    private var enabled = false
    private var isOverridden = false

    @JvmStatic
    fun stayOn() {
        if (enabled) return

        enabled = true

        try {
            DiscordIPC.start(1409302649366380605L, null)
            rpc.setStart(Instant.now().epochSecond)
            rpc.setLargeImage("https://avatars.githubusercontent.com/u/249791426", "V5")

            if (!isOverridden) {
                rpc.setDetails("Playing Skyblock")
                rpc.setState(idleStates.random())
            }

            DiscordIPC.setActivity(rpc)

            scope.launch {
                while (enabled) {
                    delay(30 * 60 * 1000L)
                    if (!isOverridden) {
                        val state = idleStates.random()
                        rpc.setState(state)
                        DiscordIPC.setActivity(rpc)
                    }
                }
            }
            logger.info("Discord RPC started")
        } catch (e: Exception) {
            logger.error("Failed to start Discord RPC", e)
            enabled = false
        }
    }

    @JvmStatic
    fun turnOff() {
        if (!enabled) return

        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        DiscordIPC.stop()

        logger.info("Discord RPC turned off")
        enabled = false
    }

    @JvmStatic
    fun updatePresence(details: String, state: String) {
        if (!enabled) stayOn()

        isOverridden = true
        rpc.setDetails(details)
        rpc.setState(state)
        DiscordIPC.setActivity(rpc)
    }

    @JvmStatic
    fun resetTimestamp() {
        if (!enabled) return
        rpc.setStart(Instant.now().epochSecond)
        DiscordIPC.setActivity(rpc)
    }

    @JvmStatic
    fun revertToIdle() {
        if (!enabled) return

        if (isOverridden) {
            isOverridden = false
            rpc.setDetails("Playing Skyblock")
            rpc.setState(idleStates.random())
            DiscordIPC.setActivity(rpc)
        }
    }
}