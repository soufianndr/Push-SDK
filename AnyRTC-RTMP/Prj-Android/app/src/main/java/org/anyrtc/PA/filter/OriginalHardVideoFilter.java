package org.anyrtc.PA.filter;

import android.opengl.GLES20;

import org.anyrtc.PA.gles.GLESTools;

import java.nio.FloatBuffer;

/**
 * Created by kanli on 12/19/16.
 */

public class OriginalHardVideoFilter extends BaseHardVideoFilter {
    protected int glProgram;
    protected int glTextureLoc;
    protected int glCamPostionLoc;
    protected int glCamTextureCoordLoc;
    protected String vertexShader_filter = "" +
            "attribute vec4 aCamPosition;\n" +
            "attribute vec2 aCamTextureCoord;\n" +
            "varying vec2 vCamTextureCoord;\n" +
            "void main(){\n" +
            "    gl_Position= aCamPosition;\n" +
            "    vCamTextureCoord = aCamTextureCoord;\n" +
            "}";
    protected String fragmentshader_filter = "" +
            "precision mediump float;\n" +
            "varying mediump vec2 vCamTextureCoord;\n" +
            "uniform sampler2D uCamTexture;\n" +
            "void main(){\n" +
            "    vec4  color = texture2D(uCamTexture, vCamTextureCoord);\n" +
            "    gl_FragColor = color;\n" +
            "}";

    public OriginalHardVideoFilter(String vertexShaderCode, String fragmentShaderCode) {
        if (vertexShaderCode != null) {
            vertexShader_filter = vertexShaderCode;
        }
        if (fragmentShaderCode != null) {
            fragmentshader_filter = fragmentShaderCode;
        }
    }

    @Override
    public void onInit(int inWidth, int inHeight, int outWidth, int outHeight) {
        super.onInit(inWidth, inHeight, outWidth, outHeight);
        glProgram = GLESTools.createProgram(vertexShader_filter, fragmentshader_filter);
        GLES20.glUseProgram(glProgram);
        glTextureLoc = GLES20.glGetUniformLocation(glProgram, "uCamTexture");
        glCamPostionLoc = GLES20.glGetAttribLocation(glProgram, "aCamPosition");
        glCamTextureCoordLoc = GLES20.glGetAttribLocation(glProgram, "aCamTextureCoord");
    }


    @Override
    public void onDraw(int cameraTexture, int targetFrameBuffer, FloatBuffer shapeBuffer, FloatBuffer textrueBuffer) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFrameBuffer);
        GLES20.glUseProgram(glProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cameraTexture);
        GLES20.glUniform1i(glTextureLoc, 0);
        GLES20.glEnableVertexAttribArray(glCamPostionLoc);
        GLES20.glEnableVertexAttribArray(glCamTextureCoordLoc);
        shapeBuffer.position(0);
        GLES20.glVertexAttribPointer(glCamPostionLoc, 2,
                GLES20.GL_FLOAT, false,
                2 * 4, shapeBuffer);
        textrueBuffer.position(0);
        GLES20.glVertexAttribPointer(glCamTextureCoordLoc, 2,
                GLES20.GL_FLOAT, false,
                2 * 4, textrueBuffer);
        onPreDraw();
        GLES20.glViewport((OUT_WIDTH-IN_WIDTH)/2, (OUT_HEIGHT - IN_HEIGHT)/2, IN_WIDTH, IN_HEIGHT);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawIndecesBuffer.limit(), GLES20.GL_UNSIGNED_SHORT, drawIndecesBuffer);
        //GLES20.glFinish();
        onAfterDraw();
        GLES20.glDisableVertexAttribArray(glCamPostionLoc);
        GLES20.glDisableVertexAttribArray(glCamTextureCoordLoc);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glUseProgram(0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    protected void onPreDraw() {

    }

    protected void onAfterDraw() {

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        GLES20.glDeleteProgram(glProgram);
    }
}
