package ru.hollowhorizon.hollowengine.client.utils

import com.mojang.blaze3d.vertex.VertexConsumer
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Matrix4fStack
import org.joml.Vector3f

fun Matrix4fStack.pushPose() = pushMatrix()
fun Matrix4fStack.popPose() = popMatrix()
fun Matrix4fStack.setIdentity() = set(identity())

fun VertexConsumer.vertex(pose: Matrix4f, x: Float, y: Float, z: Float) = addVertex(pose, x, y, z)
fun VertexConsumer.uv(x: Float, y: Float) = this.setUv( x, y)
fun VertexConsumer.uv2(x: Int) = this.setLight(x)
fun VertexConsumer.overlayCoords(x: Int) = this.setOverlay(x)
fun VertexConsumer.normal(x: Float, y: Float, z: Float) = this.setNormal(x, y, z)
fun VertexConsumer.normal(mat: Matrix3f, x: Float, y: Float, z: Float) = with(mat.transform(Vector3f(x, y, z))) { setNormal(this.x, this.y, this.z) }
fun VertexConsumer.color(r: Float, g: Float, b: Float, a: Float) = setColor(r, g, b, a)
fun VertexConsumer.color(value: Int) = setColor(value)


