package com.glumes.sample.util;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Environment;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

import static android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
import static android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME;

/**
 * Created by han.chen.
 * Date on 2021/2/24.
 **/
public class H264VideoEncoder {
    private static final String TAG = "H264VideoEncoder";
    private static H264VideoEncoder sH264VideoDecoder;

    public static H264VideoEncoder getInstance() {
        synchronized (H264VideoEncoder.class) {
            if (sH264VideoDecoder == null) {
                sH264VideoDecoder = new H264VideoEncoder();
            }
        }
        return sH264VideoDecoder;
    }

    private MediaCodec mediaCodec;
    private byte[] yuv420 = null;
    private byte[] output = null;
    private byte[] mInfo = null;
    private int mWidth = 0;
    private int mHeight = 0;
    private int mFrameRate = 15;
    private int TIMEOUT_USEC = 12000;
    public byte[] configbyte;
    private long nanoTime;


    public static ArrayBlockingQueue<byte[]> YUVQueue = new ArrayBlockingQueue<>(10);

    public void initEncoder(int width, int height, int frameRate) {
        nanoTime = System.nanoTime();
        mWidth = width;
        mHeight = height;
        mFrameRate = frameRate;
        yuv420 = new byte[width * height * 3 / 2];
        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", width, height);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, width * height * 5);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mFrameRate);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        try {
            mediaCodec = MediaCodec.createEncoderByType("video/avc");
        } catch (IOException e) {
            e.printStackTrace();
        }
        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mediaCodec.start();
        createFile();
    }

    private BufferedOutputStream outputStream;

    private void createFile() {
        File file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/test1.h264");
        if (file.exists()) {
            file.delete();
        }
        try {
            outputStream = new BufferedOutputStream(new FileOutputStream(file));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isRuning = false;

    public void startEncoderThread() {
        Thread EncoderThread = new Thread(new Runnable() {

            @Override
            public void run() {
                isRuning = true;
                byte[] input = null;

                while (isRuning) {
                    //访问MainActivity用来缓冲待解码数据的队列
                    if (YUVQueue.size() > 0) {
                        //从缓冲队列中取出一帧
                        input = YUVQueue.poll();
                        byte[] yuv420sp = new byte[mWidth * mHeight * 3 / 2];
                        //把待编码的视频帧转换为YUV420格式
                        NV21ToNV12(input, yuv420sp, mWidth, mHeight);
                        input = yuv420sp;
                    }
                    if (input != null) {
                        try {
                            //编码器输入缓冲区
                            ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();
                            //编码器输出缓冲区
                            ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();
                            int inputBufferIndex = mediaCodec.dequeueInputBuffer(-1);
                            if (inputBufferIndex >= 0) {
                                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                                inputBuffer.clear();
                                //把转换后的YUV420格式的视频帧放到编码器输入缓冲区中
                                inputBuffer.put(input);
                                mediaCodec.queueInputBuffer(inputBufferIndex, 0, input.length, (System.nanoTime() - nanoTime) / 1000, 0);
                            }

                            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                            while (outputBufferIndex >= 0) {
                                ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                                byte[] outData = new byte[bufferInfo.size];
                                outputBuffer.get(outData);
                                if (bufferInfo.flags == BUFFER_FLAG_CODEC_CONFIG) {
                                    configbyte = new byte[bufferInfo.size];
                                    configbyte = outData;
                                } else if (bufferInfo.flags == BUFFER_FLAG_KEY_FRAME) {
                                    byte[] keyframe = new byte[bufferInfo.size + configbyte.length];
                                    System.arraycopy(configbyte, 0, keyframe, 0, configbyte.length);
                                    //把编码后的视频帧从编码器输出缓冲区中拷贝出来
                                    System.arraycopy(outData, 0, keyframe, configbyte.length, outData.length);

                                    outputStream.write(keyframe, 0, keyframe.length);
                                } else {
                                    //写到文件中
                                    outputStream.write(outData, 0, outData.length);
                                }

                                mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                                outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                            }

                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    } else {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        });
        EncoderThread.start();
    }


    long pts = 0;
    long generateIndex = 0;

    public byte[] offerEncode(byte[] input) {
        NV21ToNV12(input, yuv420, mWidth, mHeight);
        try {
            ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();
            ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                pts = computePresentationTime(generateIndex);
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                inputBuffer.put(yuv420);
                mediaCodec.queueInputBuffer(inputBufferIndex, 0, yuv420.length, pts, 0);
                generateIndex += 1;
            }
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
            while (outputBufferIndex >= 0) {
                ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                output = new byte[bufferInfo.size];
                outputBuffer.get(output);
                outputStream.write(output, 0, output.length);
                mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return output;
    }

    public void close() {
        isRuning = false;
        try {
            mediaCodec.stop();
            mediaCodec.release();
            outputStream.flush();
            outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void NV21ToNV12(byte[] nv21, byte[] nv12, int width, int height) {
        if (nv21 == null || nv12 == null) return;
        int frameSize = width * height;
        int i = 0, j = 0;
        System.arraycopy(nv21, 0, nv12, 0, frameSize);
        for (i = 0; i < frameSize; i++) {
            nv12[i] = nv21[i];
        }
        for (j = 0; j < frameSize / 2; j += 2) {
            nv12[frameSize + j - 1] = nv21[j + frameSize];
        }
        for (j = 0; j < frameSize / 2; j += 2) {
            nv12[frameSize + j] = nv21[j + frameSize - 1];
        }
    }

    private long computePresentationTime(long frameIndex) {
        return 132 + frameIndex * 1000000 / mFrameRate;
    }
}
