package com.example.asr_demo;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.sinovoice.sdk.HciSdk;
import com.sinovoice.sdk.HciSdkConfig;
import com.sinovoice.sdk.LogLevel;
import com.sinovoice.sdk.android.AudioRecorder;
import com.sinovoice.sdk.android.HciAudioManager;
import com.sinovoice.sdk.android.IAudioRecorderHandler;
import com.sinovoice.sdk.asr.CloudAsrConfig;
import com.sinovoice.sdk.asr.FreetalkConfig;
import com.sinovoice.sdk.asr.FreetalkEvent;
import com.sinovoice.sdk.asr.FreetalkResult;
import com.sinovoice.sdk.asr.FreetalkShortAudio;
import com.sinovoice.sdk.asr.FreetalkStream;
import com.sinovoice.sdk.asr.IFreetalkHandler;
import com.sinovoice.sdk.asr.IShortAudioCB;
import com.sinovoice.sdk.asr.LocalAsrConfig;
import com.sinovoice.sdk.asr.ShortAudioConfig;
import com.sinovoice.sdk.asr.Warning;
import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends Activity implements IAudioRecorderHandler, IFreetalkHandler {
  // 纵向滑动灵敏度，按下录音按钮后向上滑动距离大于改值后松后调用取消识别方法，其余调用开始识别方法
  private static final float MIN_OFFSET_Y = 120l;

  // 日志窗体最大记录的行数，避免溢出问题
  private static final int MAX_LOG_LINES = 5 * 1024;
  private static final int recorderOptions = AudioRecorder.ENABLE_AEC | AudioRecorder.ENABLE_NS;

  static private HciAudioManager am; // 单例模式
  private HciSdk sdk;
  private FreetalkStream ft_stream;
  //private FreetalkStream ft_grammar;
  private FreetalkShortAudio ft_shortaudio;
  private AudioRecorder stream_recorder;
  private AudioRecorder shortaudio_recorder;
  private TextView tv_logview;
  private ImageView iv_cancel;
  private Spinner sp_mode;
  private boolean session_busy = false;
  private boolean recording = false;
  private boolean operating = false;
  private int ft_mode;
  private boolean interim_results;
  private Thread mUiThread;

  private static HciSdk createSdk(Context context) {
    String path = Environment.getExternalStorageDirectory().getAbsolutePath();
    path = path + File.separator + context.getPackageName();

    File file = new File(path);
    if (!file.exists()) {
      file.mkdirs();
    }

    HciSdk sdk = new HciSdk();
    HciSdkConfig cfg = new HciSdkConfig();
    // 平台为应用分配的 appkey
    cfg.setAppkey("aicp_app");
    // 平台为应用分配的密钥 (敏感信息，请勿公开)
    cfg.setSecret("QWxhZGRpbjpvcGVuIHNlc2FtZQ");
    cfg.setSysUrl("https://10.0.1.186:22801/");
    cfg.setCapUrl("http://10.0.1.186:22800/");
    cfg.setDataPath(path);
    Log.e("log-path", path);
    cfg.setVerifySSL(false);

    Log.w("sdk-config", cfg.toString());

    sdk.setLogLevel(LogLevel.D); // 日志级别
    sdk.init(cfg, context);
    return sdk;
  }

  private FreetalkConfig freetalkConfig() {
    FreetalkConfig config = new FreetalkConfig();
    config.setProperty("cn_16k_common");
    config.setAudioFormat("pcm_s16le_16k");
    config.setMode(ft_mode);
    //config.setAddPunc(true); // 是否打标点
    config.setInterimResults(interim_results); // 是否返回临时结果
    config.setSlice(200);
    config.setTimeout(10000);
    Log.w("config", config.toString());
    return config;
  }

  private LocalAsrConfig localAsrConfig(){
    LocalAsrConfig config = new LocalAsrConfig();
    config.setGrammar("" +
            "#JSGF V1.0;\n" +
            "\n" +
            "grammar stock_1001;\n" +
            "\n" +
            "public <stock_1001> = open |\n" +
            "万东医疗|\n" +
            "三峡水利;"
    );
    config.setModelPath("/sdcard/sinovoicedata/model_common_20190722");
    return config;
  }

  private ShortAudioConfig shortAudioConfig() {
    ShortAudioConfig config = new ShortAudioConfig();
    config.setProperty("cn_16k_common");
    config.setAudioFormat("pcm_s16le_16k");
    config.setMode(ft_mode);
    config.setAddPunc(true); // 是否打标点
    config.setTimeout(10000);
    config.setGrammarOnly(true);
    return config;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    mUiThread = Thread.currentThread();
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    sdk = createSdk(this);
    //ft_grammar = new FreetalkStream(sdk,new LocalAsrConfig());
    LocalAsrConfig localAsrConfig = new LocalAsrConfig();
    localAsrConfig.setModelPath("/sdcard/sinovoicedata/model_common_20190722");
    localAsrConfig.setGrammar("" +
            "#JSGF V1.0;\n" +
            "\n" +
            "grammar stock_1001;\n" +
            "\n" +
            "public <stock_1001> = open |\n" +
            "添气如何|\n" +
            "三峡水利;");
    ft_stream = new FreetalkStream(sdk, localAsrConfig);
    //ft_shortaudio = new FreetalkShortAudio(sdk, new CloudAsrConfig());
    if (am == null) {
      // HciAudioManager 只能创建一个实例
      am = HciAudioManager.builder(this).setSampleRate(16000).create();
    }
    // 可以创建两个 AudioRecorder，但同时只能有一个正在录音。录音机采样率与
    // HciAudioManager 采样率不同时，会进行重采样。
    stream_recorder = new AudioRecorder(am, "pcm_s16le_16k", 1000);
    shortaudio_recorder = new AudioRecorder(am, "pcm_s16le_16k", 10000);

    tv_logview = (TextView) findViewById(R.id.tv_logview);
    iv_cancel = (ImageView) findViewById(R.id.iv_cancel);
    sp_mode = (Spinner) findViewById(R.id.sp_mode);

    initEvents();
  }

  // 录音按钮按下
  private void onRecordButtonDown() {
    if (session_busy || recording) {
      return; // 正在识别或正在录音时直接返回
    }
    // 选项序号与识别模式的取值一致
    ft_mode = sp_mode.getSelectedItemPosition();

    if (ft_mode == ShortAudioConfig.SHORT_AUDIO_MODE) {
      // 录音启动成功并且结束后，再进行识别
      printLog("启动录音");
      recording = shortaudio_recorder.start(recorderOptions, this);
    } else {
      // 识别会话启动成功后再进行识别
      FreetalkConfig config = freetalkConfig();
      //LocalAsrConfig config = localAsrConfig();
      session_busy = true;
      printLog("启动识别会话");
      ft_stream.start(config, stream_recorder.audioSource(), this, true);
     // ft_grammar.start(config,stream_recorder.audioSource(),this,true);
    }
  }

  // 录音按钮抬起
  private void onRecordButtonUp(boolean cancel) {
    if (!recording) {
      return;
    }
    if (ft_mode == ShortAudioConfig.SHORT_AUDIO_MODE) {
      shortaudio_recorder.stop(cancel);
    } else {
      stream_recorder.stop(cancel);
    }
  }

  private void initEvents() {
    Button btn;

    // 录音识别按钮
    btn = (Button) findViewById(R.id.bt_record);
    btn.setOnTouchListener(new OnTouchListener() {
      private float downY;

      @Override
      public boolean onTouch(View v, MotionEvent event) {
        switch (event.getAction()) {
          case MotionEvent.ACTION_DOWN:
            iv_cancel.setVisibility(View.INVISIBLE);
            if (session_busy || recording) {
              return true; // 会话忙碌中或者录音机还在工作，不允许操作
            }
            operating = true;
            downY = event.getY(); // 记录按下时的纵坐标，用于计算纵向偏移
            onRecordButtonDown();
            break;
          case MotionEvent.ACTION_MOVE: // 实时计算纵向偏移量，根据条件决定是否显示取消提示窗体
            if (!operating) {
              return true;
            }
            final boolean visib = downY - event.getY() > MIN_OFFSET_Y;
            if (visib && iv_cancel.getVisibility() != View.VISIBLE) {
              iv_cancel.setVisibility(View.VISIBLE);
            } else if (!visib && iv_cancel.getVisibility() == View.VISIBLE) {
              iv_cancel.setVisibility(View.INVISIBLE);
            }
            break;
          case MotionEvent.ACTION_CANCEL:
          case MotionEvent.ACTION_UP: // 抬手时，根据纵坐标偏移，决定开始识别还是取消识别
            iv_cancel.setVisibility(View.INVISIBLE);
            if (!operating) {
              return true;
            }
            operating = false;
            boolean cancel = downY - event.getY() > MIN_OFFSET_Y;
            onRecordButtonUp(cancel);
        }
        return false;
      }
    });

    // 清屏按钮
    btn = (Button) findViewById(R.id.bt_clear);
    btn.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            tv_logview.setText("");
          }
        });
      }
    });

    // 关闭 SDK 按钮
    btn = (Button) findViewById(R.id.bt_close);
    btn.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View arg0) {
        sdk.close();
        findViewById(R.id.bt_record).setEnabled(false);
      }
    });
    CheckBox cb;
    cb = (CheckBox) findViewById(R.id.ck_interim_result);
    cb.setOnCheckedChangeListener(new OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton cb, boolean checked) {
        interim_results = checked;
      }
    });
  }

  private final Runnable scrollLog = new Runnable() {
    @Override
    public void run() {
      ((ScrollView) tv_logview.getParent()).fullScroll(ScrollView.FOCUS_DOWN);
    }
  };

  private void _printLog(String detail) {
    // 日志输出同时记录到日志文件中
    if (tv_logview == null) {
      return;
    }

    // 如日志行数大于上限，则清空日志内容
    if (tv_logview.getLineCount() > MAX_LOG_LINES) {
      tv_logview.setText("");
    }

    // 在当前基础上追加日志
    tv_logview.append(detail + "\n");

    // 二次刷新确保父控件向下滚动能到达底部,解决一次出现多行日志时滚动不到底部的问题
    tv_logview.post(scrollLog);
  }

  private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS");

  private void printLog(String detail) {
    final String message = fmt.format(new Date()) + " " + detail;
    if (mUiThread == Thread.currentThread()) {
      _printLog(message);
      return;
    }
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        _printLog(message);
      }
    });
  }

  @Override
  public void onAudio(AudioRecorder recorder, ByteBuffer audio) {
    // 反馈录音数据，调用频率较高，一般不做处理
  }

  @Override
  public void onBufferFull(AudioRecorder recorder) {
    printLog("录音缓冲区满");
    recorder.stop(false);
  }

  @Override
  public void onError(AudioRecorder recorder, String message) {
    printLog("录音发生错误: " + message);
    recorder.stop(true);
  }

  @Override
  public void onSourceEnded(AudioRecorder recorder) {
    printLog("录音机音频源停止读取");
    recorder.stop(true);
  }

  @Override
  public void onStart(AudioRecorder recorder) {
    printLog("录音已启动");
  }

  @Override
  public void onStartFail(AudioRecorder recorder, String message) {
    printLog("录音启动失败: " + message);
  }

  @Override
  public void onStop(AudioRecorder recorder) {
    printLog("录音已停止");
    recording = false;
    if (recorder == shortaudio_recorder) {
      int timelen = shortaudio_recorder.bufferTimeLen();
      ByteBuffer audio_data = shortaudio_recorder.readAll();
      ShortAudioConfig config = shortAudioConfig();
      //LocalAsrConfig config = localAsrConfig();
      printLog("音频数据长度: " + audio_data.limit());
      printLog("音频数据时长: " + timelen);
      session_busy = true;
      ft_shortaudio.recognize(config, audio_data, new IShortAudioCB() {
        @Override
        public void run(FreetalkShortAudio s, int code, FreetalkResult res, Warning[] warnings) {
          session_busy = false;
          if (code != 0) {
            printLog("一句话识别失败，code = " + code);
          } else {
            printLog("一句话识别成功，result = " + res.toString());
          }
        }
      });
    }
  }

  @Override
  public void onStart(final FreetalkStream s, int code, Warning[] warnings) {
    if (code == 0) {
      printLog("FreetalkStream 会话启动成功，启动录音");
      runOnUiThread(new Runnable() {
        @Override
        public void run() {
          // 启动录音机为识别会话提供音频数据
          recording = stream_recorder.start(recorderOptions, MainActivity.this);
          if (!recording) {
            printLog("取消会话");
            s.stop(true);
          }
        }
      });
    } else {
      printLog("FreetalkStream 识别会话启动失败, code = " + code);
      session_busy = false;
    }
  }

  @Override
  public void onEnd(FreetalkStream s, int reason) {
    printLog("FreetalkStream 识别会话结束, reason = " + reason);
    session_busy = false;
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        // 如果还在录音，停止它
        stream_recorder.stop(true);
      }
    });
  }

  @Override
  public void onError(FreetalkStream s, int code) {
    printLog("识别发生错误: code = " + code);
  }

  @Override
  public void onEvent(FreetalkStream s, FreetalkEvent event) {
    // event 仅可在本回调内使用，如果需要缓存 event，请调用 event.clone()
    printLog("FreetalkStream 语音事件，event = " + event.toString());
  }

  @Override
  public void onResult(FreetalkStream s, FreetalkResult sentence) {
    // sentence 仅可在本回调内使用，如果需要缓存 sentence，请调用 sentence.clone()
    printLog("FreetalkStream 识别结果，sentence = " + sentence.toString());
  }
}
