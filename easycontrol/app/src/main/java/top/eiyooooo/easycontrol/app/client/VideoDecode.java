package top.eiyooooo.easycontrol.app.client;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.util.Pair;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

import top.eiyooooo.easycontrol.app.helper.L;
import top.eiyooooo.easycontrol.app.helper.MediaCodecHelper;

public class VideoDecode {
  private MediaCodec decodec;
  private final MediaCodec.Callback callback = new MediaCodec.Callback() {
    @Override
    public void onInputBufferAvailable(MediaCodec mediaCodec, int inIndex) {
      intputBufferQueue.offer(inIndex);
      checkDecode();
    }

    @Override
    public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int outIndex, @NonNull MediaCodec.BufferInfo bufferInfo) {
      mediaCodec.releaseOutputBuffer(outIndex, bufferInfo.presentationTimeUs);
    }

    @Override
    public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
    }

    @Override
    public void onOutputFormatChanged(MediaCodec mediaCodec, MediaFormat format) {
    }
  };

  private final String uuid;

  public VideoDecode(String uuid, Pair<Integer, Integer> videoSize, Surface surface, Pair<byte[], Long> csd0, Pair<byte[], Long> csd1, Handler handler) throws IOException {
    this.uuid = uuid;
    setVideoDecodec(videoSize, surface, csd0, csd1, handler);
  }

  public void release() {
    try {
      decodec.stop();
      decodec.release();
    } catch (Exception ignored) {
    }
  }

  private final LinkedBlockingQueue<Pair<byte[], Long>> intputDataQueue = new LinkedBlockingQueue<>();
  private final LinkedBlockingQueue<Integer> intputBufferQueue = new LinkedBlockingQueue<>();

  public void decodeIn(byte[] data, long pts) {
    intputDataQueue.offer(new Pair<>(data, pts));
    checkDecode();
  }

  private synchronized void checkDecode() {
    if (intputDataQueue.isEmpty() || intputBufferQueue.isEmpty()) return;
    Integer inIndex = intputBufferQueue.poll();
    Pair<byte[], Long> data = intputDataQueue.poll();
    decodec.getInputBuffer(inIndex).put(data.first);
    decodec.queueInputBuffer(inIndex, 0, data.first.length, data.second, 0);
    checkDecode();
  }

  // 创建Codec
  private void setVideoDecodec(Pair<Integer, Integer> videoSize, Surface surface, Pair<byte[], Long> csd0, Pair<byte[], Long> csd1, Handler handler) throws IOException {
    boolean isH265Support = csd1 == null;
    // 创建解码器
    String codecMime = isH265Support ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
    MediaFormat decodecFormat = getLowLatencyFormat(videoSize, codecMime);
    decodec = MediaCodec.createDecoderByType(codecMime);
    // 获取视频标识头
    decodecFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd0.first));
    if (!isH265Support) decodecFormat.setByteBuffer("csd-1", ByteBuffer.wrap(csd1.first));
    // 异步解码
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      decodec.setCallback(callback, handler);
    } else decodec.setCallback(callback);
    // 配置解码器
    decodec.configure(decodecFormat, surface, null, 0);
    // 启动解码器
    decodec.start();
    // 解析首帧，解决开始黑屏问题
    decodeIn(csd0.first, csd0.second);
    if (!isH265Support) decodeIn(csd1.first, csd1.second);
  }

  private MediaFormat getLowLatencyFormat(Pair<Integer, Integer> videoSize, String codecMime) throws IOException {
    MediaCodec decodec = MediaCodec.createDecoderByType(codecMime);
    MediaCodecInfo decoderInfo = decodec.getCodecInfo();

    for (int tryNumber = 0; ; tryNumber++) {
      // L.log(uuid, "Decoder configuration try: " + tryNumber);

      MediaFormat mediaFormat = MediaFormat.createVideoFormat(codecMime, videoSize.first, videoSize.second);

      // This will try low latency options until we find one that works (or we give up).
      boolean newFormat = MediaCodecHelper.setDecoderLowLatencyOptions(mediaFormat, decoderInfo, tryNumber);

      // Throw the underlying codec exception on the last attempt if the caller requested it
      if (tryConfigureDecoder(decoderInfo, mediaFormat, false)) {
        decodec.release();
        // Success!
        return mediaFormat;
      }

      if (!newFormat) {
        decodec.release();
        // We couldn't even configure a decoder without any low latency options
        return MediaFormat.createVideoFormat(codecMime, videoSize.first, videoSize.second);
      }
    }
  }

  private boolean tryConfigureDecoder(MediaCodecInfo selectedDecoderInfo, MediaFormat format, boolean throwOnCodecError) {
    boolean configured = false;
    try {
      decodec = MediaCodec.createByCodecName(selectedDecoderInfo.getName());
      decodec.configure(format, null, null, 0);
      decodec.start();
      L.log(uuid, "Using codec " + selectedDecoderInfo.getName() + " for hardware decoding " + format.getString(MediaFormat.KEY_MIME));
      configured = true;
    } catch (IllegalArgumentException e) {
      e.printStackTrace();
      if (throwOnCodecError) {
        throw e;
      }
    } catch (IllegalStateException e) {
      e.printStackTrace();
      if (throwOnCodecError) {
        throw e;
      }
    } catch (IOException e) {
      e.printStackTrace();
      if (throwOnCodecError) {
        throw new RuntimeException(e);
      }
    } finally {
      if (!configured && decodec != null) {
        decodec.release();
        decodec = null;
      }
    }
    return configured;
  }
}
