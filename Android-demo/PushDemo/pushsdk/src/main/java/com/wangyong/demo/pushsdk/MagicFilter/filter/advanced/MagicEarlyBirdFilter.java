package com.wangyong.demo.pushsdk.MagicFilter.filter.advanced;

import android.opengl.GLES20;

import com.wangyong.demo.pushsdk.R;
import com.wangyong.demo.pushsdk.MagicFilter.filter.base.gpuimage.GPUImageFilter;
import com.wangyong.demo.pushsdk.MagicFilter.utils.MagicParams;
import com.wangyong.demo.pushsdk.MagicFilter.utils.OpenGlUtils;

public class MagicEarlyBirdFilter extends GPUImageFilter{
	private int[] inputTextureHandles = {-1,-1,-1,-1,-1};
	private int[] inputTextureUniformLocations = {-1,-1,-1,-1,-1};
	protected int mGLStrengthLocation;

	public MagicEarlyBirdFilter(){
		super(NO_FILTER_VERTEX_SHADER, OpenGlUtils.readShaderFromRawResource(R.raw.earlybird));
	}
	
	protected void onDestroy() {
        super.onDestroy();
        GLES20.glDeleteTextures(inputTextureHandles.length, inputTextureHandles, 0);
        for(int i = 0; i < inputTextureHandles.length; i++)
        	inputTextureHandles[i] = -1;
    }
	
	protected void onDrawArraysAfter(){
		for(int i = 0; i < inputTextureHandles.length
				&& inputTextureHandles[i] != OpenGlUtils.NO_TEXTURE; i++){
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + (i+3));
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
		}
	}
	  
	protected void onDrawArraysPre(){
		for(int i = 0; i < inputTextureHandles.length 
				&& inputTextureHandles[i] != OpenGlUtils.NO_TEXTURE; i++){
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + (i+3) );
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureHandles[i]);
			GLES20.glUniform1i(inputTextureUniformLocations[i], (i+3));
		}
	}
	
	protected void onInit(){
		super.onInit();
		for(int i=0; i < inputTextureUniformLocations.length; i++)
			inputTextureUniformLocations[i] = GLES20.glGetUniformLocation(getProgram(), "inputImageTexture"+(2+i));
		mGLStrengthLocation = GLES20.glGetUniformLocation(mGLProgId,
				"strength");
	}
	
	protected void onInitialized(){
		super.onInitialized();
		setFloat(mGLStrengthLocation, 1.0f);
	    runOnDraw(new Runnable(){
		    public void run(){
		    	inputTextureHandles[0] = OpenGlUtils.loadTexture(MagicParams.context, "filter/earlybirdcurves.png");
				inputTextureHandles[1] = OpenGlUtils.loadTexture(MagicParams.context, "filter/earlybirdoverlaymap_new.png");
				inputTextureHandles[2] = OpenGlUtils.loadTexture(MagicParams.context, "filter/vignettemap_new.png");
				inputTextureHandles[3] = OpenGlUtils.loadTexture(MagicParams.context, "filter/earlybirdblowout.png");
				inputTextureHandles[4] = OpenGlUtils.loadTexture(MagicParams.context, "filter/earlybirdmap.png");
		    }
	    });
	}
}
