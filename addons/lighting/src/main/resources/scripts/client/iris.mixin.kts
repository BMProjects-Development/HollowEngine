@file:ClientSide

import net.irisshaders.iris.gl.IrisRenderSystem
import net.irisshaders.iris.gl.program.ProgramSamplers
import net.irisshaders.iris.gl.sampler.SamplerBinding
import net.irisshaders.iris.uniforms.CapturedRenderingState
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.qualet.irl.light.FramePipeline
import org.qualet.irl.light.IrlSamplers
import org.qualet.irl.light.iris.IrlSamplersBind

mixin<ProgramSamplers.Builder> {
    method("build") {
        before { IrlSamplersBind.bindAll(self) }
    }
}

// The wrapped call supplies both private fields as ordinary arguments; no reflection or accessors are needed.
mixin<SamplerBinding> {
    wrapOperation("updateSampler") {
        at(call<IrisRenderSystem>("bindTextureToUnit(III)V"))
        code { _, arguments ->
            val target = IrlSamplers.glTargetForTexture(arguments[2])
            if (target != GL11.GL_TEXTURE_2D) arguments[0] = target
            proceed()
        }
    }
}

mixin<CapturedRenderingState> {
    method("setGbufferProjection") {
        after {
            FramePipeline.onGbufferMatricesCaptured(Matrix4f(self.gbufferModelView), Matrix4f(self.gbufferProjection))
        }
    }
}
