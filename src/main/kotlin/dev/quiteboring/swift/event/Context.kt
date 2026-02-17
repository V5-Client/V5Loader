package dev.quiteboring.swift.event

import net.minecraft.client.render.Camera
import net.minecraft.client.render.Frustum
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.math.MatrixStack

class Context {

  var matrixStack: MatrixStack? = null
  lateinit var consumers: VertexConsumerProvider
  lateinit var camera: Camera
  lateinit var frustum: Frustum

}
