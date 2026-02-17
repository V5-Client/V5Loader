package dev.quiteboring.swift.util.render

import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayer.MultiPhase
import net.minecraft.client.render.RenderPhase

object Layers {

  val LINE_LIST: RenderLayer = RenderLayer.of(
    "line-list", RenderLayer.DEFAULT_BUFFER_SIZE,
    Pipelines.LINE_LIST,
    RenderLayer.MultiPhaseParameters.builder()
      .layering(RenderPhase.VIEW_OFFSET_Z_LAYERING)
      .build(false)
  )

  val LINE_LIST_ESP: RenderLayer = RenderLayer.of(
    "line-list-esp", RenderLayer.DEFAULT_BUFFER_SIZE,
    Pipelines.LINE_LIST_ESP,
    RenderLayer.MultiPhaseParameters.builder().build(false)
  )

  val TRIANGLE_STRIP: MultiPhase = RenderLayer.of(
    "triangle_strip", RenderLayer.DEFAULT_BUFFER_SIZE,
    false, true,
    Pipelines.TRIANGLE_STRIP,
    RenderLayer.MultiPhaseParameters.builder()
      .layering(RenderPhase.VIEW_OFFSET_Z_LAYERING)
      .build(false)
  )

  val TRIANGLE_STRIP_ESP: MultiPhase = RenderLayer.of(
    "triangle_strip_esp", RenderLayer.DEFAULT_BUFFER_SIZE,
    false, true,
    Pipelines.TRIANGLE_STRIP_ESP,
    RenderLayer.MultiPhaseParameters.builder().build(false)
  )

}
