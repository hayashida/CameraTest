package jp.kuseful.cameratest;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.Size;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;

public class CameraTestActivity extends Activity {
	
	private Camera myCamera;
	private SurfaceView mySurfaceView;
	
	private final SimpleDateFormat photoName = new SimpleDateFormat("yyy-MM-dd-HHmmss", Locale.JAPAN);
	
	private SensorManager mySensor;
	
	private static final int MATRIX_SIZE = 16;
	private static final int DIMENSION = 3;
	
	private float[] magneticValues = new float[DIMENSION];
	private float[] accelerometerValues = new float[DIMENSION];
	private float[] orientationValues = new float[DIMENSION];
	
	/**
	 * 写真を撮る
	 */
	private PictureCallback mPictureListener = 
			new PictureCallback() {
				
				@Override
				public void onPictureTaken(byte[] data, Camera camera) {
					ContentResolver resolver = getContentResolver();
					
					// 日時からファイル名を生成
					Date token = Calendar.getInstance().getTime();
					String name = photoName.format(token) + ".jpg";
					
					// データを生成する
					Bitmap tmp_bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
					int width = tmp_bitmap.getWidth();
					int height = tmp_bitmap.getHeight();

					// 画像データを回転する
					int rad_y = radianToDegree(orientationValues[2]);
					Matrix matrix = new Matrix();
					if ((rad_y > -45 && rad_y <= 0) || (rad_y > 0 && rad_y <= 45)) {
						matrix.setRotate(90);
					} else if (rad_y > 45 && rad_y <= 135) {
						matrix.setRotate(180);
					} else if ((rad_y > 135 && rad_y <= 180) || (rad_y >= -180 && rad_y <= -135)) {
						matrix.setRotate(-90);
					} else if (rad_y > -135 && rad_y <= -45) {
						matrix.setRotate(0);
					}
					
					// 画像データを保存する
					Bitmap bitmap = Bitmap.createBitmap(tmp_bitmap, 0, 0, width, height, matrix, true);
					MediaStore.Images.Media.insertImage(resolver, bitmap, name, null);
					
					// カメラを再開
					myCamera.startPreview();
				}
			};
	
	/**
	 * SurfaceView
	 * 生成・変更・破棄
	 */
	private SurfaceHolder.Callback mSurfaceListener = 
			new SurfaceHolder.Callback() {
				@Override
				public void surfaceDestroyed(SurfaceHolder holder) {
					myCamera.stopPreview();
					myCamera.release();
					myCamera = null;
				}
				
				@Override
				public void surfaceCreated(SurfaceHolder holder) {
					myCamera = Camera.open();
					try {
						myCamera.setPreviewDisplay(holder);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				
				@Override
				public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
					myCamera.stopPreview();
					
					Camera.Parameters parameters = myCamera.getParameters();

					// 画面の向きを設定
					boolean portrait = isPortrait();
					if (portrait) {
						myCamera.setDisplayOrientation(90);
					} else {
						myCamera.setDisplayOrientation(0);
					}
					
					// 対応するプレビューサイズ・保存サイズを取得する
					List<Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();
					List<Camera.Size> pictureSizes = parameters.getSupportedPictureSizes();
					
					Size previewSize = getOptimalPreviewSize(previewSizes, width, height);
					Size pictureSize = pictureSizes.get(0);
					
					Log.d("CameraTest", "surface = " + String.valueOf(width) + " , " + String.valueOf(height));
					Log.d("CameraTest", "preview = " + String.valueOf(previewSize.width) + " , " + String.valueOf(previewSize.height));
					Log.d("CameraTest", "picture = " + String.valueOf(pictureSize.width) + " , " + String.valueOf(pictureSize.height));

					parameters.setPreviewSize(previewSize.width, previewSize.height);
					parameters.setPictureSize(pictureSize.width, pictureSize.height);
					
					// カメラプレビューレイアウトの設定
					int previewWidth = 640;
					int previewHeight = 480;
					android.view.ViewGroup.LayoutParams layoutParams = mySurfaceView.getLayoutParams();
					if (portrait) {
						layoutParams.width = previewHeight;
						layoutParams.height = previewWidth;
					} else {
						layoutParams.width = previewWidth;
						layoutParams.height = previewHeight;
					}
					mySurfaceView.setLayoutParams(layoutParams);

					// パラメータを設定してカメラを再開
					myCamera.setParameters(parameters);
					myCamera.startPreview();
				}
			};
			
	/**
	 * オートフォーカス処理
	 */
	private AutoFocusCallback mAutoFocusListener = 
			new AutoFocusCallback() {
				
				@Override
				public void onAutoFocus(boolean success, Camera camera) { }
			};
	
	/**
	 * センサー制御
	 */
	private SensorEventListener mSensorEventListener =
			new SensorEventListener() {
				
				@Override
				public void onSensorChanged(SensorEvent event) {
					if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE)
						return;
					
					switch (event.sensor.getType()) {
						case Sensor.TYPE_MAGNETIC_FIELD:
							// 地磁気センサ
							magneticValues = event.values.clone();
							break;
						case Sensor.TYPE_ACCELEROMETER:
							// 加速度センサ
							accelerometerValues = event.values.clone();
							break;
					}
					
					if (magneticValues != null && accelerometerValues != null) {
						float[] rotationMatrix = new float[MATRIX_SIZE];
						float[] inclinationMatrix = new float[MATRIX_SIZE];
						float[] remapedMatrix = new float[MATRIX_SIZE];
						
						// 加速度センサと地磁気センタから回転行列を取得
						SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, accelerometerValues, magneticValues);
						
						SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapedMatrix);
						SensorManager.getOrientation(remapedMatrix, orientationValues);
					}
				}
				
				@Override
				public void onAccuracyChanged(Sensor sensor, int accuracy) { }
			};
			
	/**
	 * 画面の向きを取得する
	 * @return
	 */
	private boolean isPortrait() {
		return (this.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT);
	}
	
	private Size getOptimalPreviewSize(List<Size> sizes, int w, int h) {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) w / h;
        if (sizes == null) return null;

        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        int targetHeight = h;

        // Try to find an size match aspect ratio and size
        for (Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

	private int radianToDegree(float rad) {
		return (int)Math.floor(Math.toDegrees(rad));
	}
	
	/**
	 * 画面タッチ時でオートフォーカスを実装
	 */
	public boolean onTouchEvent(MotionEvent event) {
		if (event.getAction() == MotionEvent.ACTION_DOWN) {
			Camera.Parameters params = myCamera.getParameters();
			if (!params.getFocusMode().equals(Camera.Parameters.FOCUS_MODE_FIXED)) {
				myCamera.autoFocus(mAutoFocusListener);
			}
		}
		return true;
	}
	
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_CAMERA
				|| keyCode == KeyEvent.KEYCODE_VOLUME_UP
				|| keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
			myCamera.takePicture(null, null, mPictureListener);
			return true;
		}
		
		return super.onKeyDown(keyCode, event);
	}
			
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全画面設定
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        
        setContentView(R.layout.main);

        // カメラプレビューの設定
        mySurfaceView = (SurfaceView)findViewById(R.id.surface_view);
        SurfaceHolder holder = mySurfaceView.getHolder();
        holder.addCallback(mSurfaceListener);
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        
        // センサーを取得する
        mySensor = (SensorManager)getSystemService(SENSOR_SERVICE);
    }
    
    @Override
    public void onResume() {
    	super.onResume();
    	
    	// 地磁気センサ
    	mySensor.registerListener(mSensorEventListener,
    								mySensor.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
    								SensorManager.SENSOR_DELAY_UI);
    	
    	// 加速度センサ
    	mySensor.registerListener(mSensorEventListener,
    								mySensor.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
    								SensorManager.SENSOR_DELAY_UI);
    }
    
    @Override
    public void onPause() {
    	super.onPause();
    	mySensor.unregisterListener(mSensorEventListener);
    }
}