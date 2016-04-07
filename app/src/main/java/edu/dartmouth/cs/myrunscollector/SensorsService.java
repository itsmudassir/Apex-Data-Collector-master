/**
 * LocationService.java
 *
 * Created by Xiaochao Yang on Sep 11, 2011 4:50:19 PM
 *
 */

package edu.dartmouth.cs.myrunscollector;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.ArffSaver;
import weka.core.converters.ConverterUtils.DataSource;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import com.meapsoft.FFT;

public class SensorsService extends Service implements SensorEventListener {

	private static final int mFeatLen = Globals.ACCELEROMETER_BLOCK_CAPACITY +72 ;   //8+64
	//private double[] gravity = new double[3];

	private File mFeatureFile;
	private SensorManager mSensorManager;
	private Sensor mAccelerometer;
	private int mServiceTaskType;
	private String mLabel;
	private Instances mDataset;
	private Attribute mClassAttribute;
	private OnSensorChangedTask mAsyncTask;

	private static ArrayBlockingQueue<Sample> mAccBuffer;

	public static final DecimalFormat mdf = new DecimalFormat("#.##");
	private boolean actulAccelero=false;
	private boolean acutalGyro=false;


	@Override
	public void onCreate() {
		super.onCreate();

		mAccBuffer = new ArrayBlockingQueue<Sample>(
				Globals.ACCELEROMETER_BUFFER_CAPACITY);

	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {

		mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		mAccelerometer = mSensorManager
				.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);

		mSensorManager.registerListener(this, mAccelerometer,
				SensorManager.SENSOR_DELAY_FASTEST);

		mSensorManager.registerListener(this,
				mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
				SensorManager.SENSOR_DELAY_FASTEST);

		Bundle extras = intent.getExtras();
		mLabel = extras.getString(Globals.CLASS_LABEL_KEY);

		mFeatureFile = new File(getExternalFilesDir(null), Globals.FEATURE_FILE_NAME);
		Log.d(Globals.TAG, mFeatureFile.getAbsolutePath());

		mServiceTaskType = Globals.SERVICE_TASK_TYPE_COLLECT;

		// Create the container for attributes
		ArrayList<Attribute> allAttr = new ArrayList<Attribute>();

		// Adding FFT coefficient attributes
		DecimalFormat df = new DecimalFormat("0000");

		for (int i = 0; i < Globals.ACCELEROMETER_BLOCK_CAPACITY; i++) {
			allAttr.add(new Attribute(Globals.FEAT_FFT_COEF_LABEL + df.format(i)));
		}

		for (int i = 64; i < Globals.ACCELEROMETER_BLOCK_CAPACITY+64; i++) {
			allAttr.add(new Attribute(Globals.FEAT_FFT_COEF_LABEL + df.format(i)));  //so that it starts at 64 and goes till  127
		}
		// Adding the max feature
		allAttr.add(new Attribute(Globals.FEAT_MAX_LABEL));  //128th indexx
		allAttr.add(new Attribute(Globals.FEAT_X_MEAN_LABEL));
		allAttr.add(new Attribute(Globals.FEAT_Y_MEAN_LABEL));

		allAttr.add(new Attribute(Globals.FEAT_Z_MEAN_LABEL));
		allAttr.add(new Attribute(Globals.FEAT_X_STD_LABEL));
		allAttr.add(new Attribute(Globals.FEAT_Y_STD_LABEL));

		allAttr.add(new Attribute(Globals.FEAT_Z_STD_LABEL));
		// Declare a nominal attribute along with its candidate values
		ArrayList<String> labelItems = new ArrayList<String>(10);
		labelItems.add(Globals.CLASS_LABEL_STANDING);
		labelItems.add(Globals.CLASS_LABEL_WALKING);
		labelItems.add(Globals.CLASS_LABEL_RUNNING);
		labelItems.add(Globals.CLASS_LABEL_SITTING);
		labelItems.add(Globals.CLASS_LABEL_LAYING);
		labelItems.add(Globals.CLASS_LABEL_IDLE);
		labelItems.add(Globals.CLASS_LABEL_UPSTAIRS);
		labelItems.add(Globals.CLASS_LABEL_DOWNSTAIRS);


		labelItems.add(Globals.CLASS_LABEL_OTHER);
		mClassAttribute = new Attribute(Globals.CLASS_LABEL_KEY, labelItems);
		allAttr.add(mClassAttribute);

		// Construct the dataset with the attributes specified as allAttr and
		// capacity 10000
		mDataset = new Instances(Globals.FEAT_SET_NAME, allAttr, Globals.FEATURE_SET_CAPACITY);

		// Set the last column/attribute (standing/walking/running) as the class
		// index for classification
		mDataset.setClassIndex(mDataset.numAttributes() - 1);

		Intent i = new Intent(this, CollectorActivity.class);
		// Read:
		// http://developer.android.com/guide/topics/manifest/activity-element.html#lmode
		// IMPORTANT!. no re-create activity
		i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

		PendingIntent pi = PendingIntent.getActivity(this, 0, i, 0);

		Notification notification = new Notification.Builder(this)
				.setContentTitle(
						getApplicationContext().getString(
								R.string.ui_sensor_service_notification_title))
				.setContentText(
						getResources()
								.getString(
										R.string.ui_sensor_service_notification_content))
				.setSmallIcon(R.drawable.greend).setContentIntent(pi).build();
		NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		notification.flags = notification.flags
				| Notification.FLAG_ONGOING_EVENT;
		notificationManager.notify(0, notification);

		mAsyncTask = new OnSensorChangedTask();
		mAsyncTask.execute();

		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy() {
		mAsyncTask.cancel(true);
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		mSensorManager.unregisterListener(this);
		Log.i("","");
		super.onDestroy();

	}
	public static void normalize(double[] ra, double max) {
		for (int i=0; i < ra.length; i++)
			ra[i] = ra[i] / max ;

	}

	private class OnSensorChangedTask extends AsyncTask<Void, Void, Void> {
		public Gesture sampleSignal(Gesture signal) {
			ArrayList<double[]> sampledValues = new ArrayList<double[]>(signal.length());
			Gesture sampledSignal = new Gesture(sampledValues, signal.getLabel());

			double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
			for (int i = 0; i < signal.length(); i++) {
				for (int j = 0; j < 3; j++) {
					if (signal.getValue(i, j) > max) {
						max = signal.getValue(i, j);
					}
					if (signal.getValue(i, j) < min) {
						min = signal.getValue(i, j);
					}
				}
			}
			for (int i = 0; i < signal.length(); ++i) {
				sampledValues.add(new double[3]);
				for (int j = 0; j < 3; ++j) {
					sampledSignal.setValue(i, j, (signal.getValue(i, j) - min) / (max - min));
				}
			}
			return sampledSignal;

		}


		private double computeMean(double values[]){
			double mean = 0.0;
			for(int i=0;i<values.length;i++)
				mean += values[i];
			return mean/values.length;
		}

		private double computeStdDev(double values[],double mean){
			double dev = 0.0;
			double diff = 0.0;
			for(int i=0;i<values.length;i++){
				diff = values[i]-mean;
				dev += diff*diff;
			}
			return Math.sqrt(dev/values.length);
		}
		@Override
		protected Void doInBackground(Void... arg0) {

			Instance inst = new DenseInstance(mFeatLen);
			inst.setDataset(mDataset);
			int blockSize = 0;
			int count=0;
			FFT fft = new FFT(Globals.ACCELEROMETER_BLOCK_CAPACITY);
			double[] accBlock = new double[Globals.ACCELEROMETER_BLOCK_CAPACITY];
			double[] xBlock = new double[Globals.ACCELEROMETER_BLOCK_CAPACITY];
			double[] yBlock = new double[Globals.ACCELEROMETER_BLOCK_CAPACITY];
			double[] zBlock = new double[Globals.ACCELEROMETER_BLOCK_CAPACITY];
			double[] rBlock = new double[Globals.ACCELEROMETER_BLOCK_CAPACITY];

			double[] x = new double[Globals.ACCELEROMETER_BLOCK_CAPACITY];
			double[] y = new double[Globals.ACCELEROMETER_BLOCK_CAPACITY];
			double[] z = new double[Globals.ACCELEROMETER_BLOCK_CAPACITY];



			double[] re = accBlock;
			double[] im = new double[Globals.ACCELEROMETER_BLOCK_CAPACITY];
			double[] Rre = rBlock;
			double[] Rim = new double[Globals.ACCELEROMETER_BLOCK_CAPACITY];
			ArrayList<double[]> gestureValues = new ArrayList<double[]>();
			double max = Double.MIN_VALUE;
			double meanX,meanY,meanZ,stdDevX,stdDevY,stdDevZ;
			//double[] x,y,z;
			while (true) {
				try {
					// need to check if the AsyncTask is cancelled or not in the while loop
					if (isCancelled () == true)
					{
						return null;
					}
					count= blockSize++;
					// Dumping buffer
					xBlock[count] = mAccBuffer.take().getX();
					yBlock[count] = mAccBuffer.take().getY();
					zBlock[count] = mAccBuffer.take().getZ();
					rBlock[count] = mAccBuffer.take().getRotX();
//Log.d("apex","rBlock"+rBlock);

					double[] value = {xBlock[count],yBlock[count] ,zBlock[count] };
					gestureValues.add(value);

					//accBlock[count] = Math.sqrt(xBlock[count]*xBlock[count]+yBlock[count]*yBlock[count]+zBlock[count]*zBlock[count]);
//Log.d("Mags"," "+accBlock[count]);
					if (blockSize == Globals.ACCELEROMETER_BLOCK_CAPACITY) {
						blockSize = 0;
						count=0;
						meanX=0;
						stdDevX=0;
						meanY=0;
						stdDevY=0;
						meanZ=0;
						stdDevZ=0;

						Gesture normSignal = sampleSignal(new Gesture(gestureValues, null));

						//double[] x = normSignal.getValue(1,0);
						for (int p = 0; p < normSignal.length(); ++p) {

								int a = 0;
								x[p] = normSignal.getValue(p, a);
								y[p] = normSignal.getValue(p, a++);
								z[p] = normSignal.getValue(p, a++);
								accBlock[p] = Math.sqrt(x[p] * x[p] + y[p] * y[p] + z[p] * z[p]);
//Log.d("Norm Count"," "+p);
							}
						for(int i=0;i<accBlock.length;i++){

							//Log.d("apexr","acc "+accBlock[i]);

						}
						for(int i=0;i<rBlock.length;i++){

						//	Log.d("apexr","rblo "+rBlock[i]);

						}
							//double[] y= xyz.get(1);
							//double[] z= xyz.get(2);
							///Log.d("X length = " + xyz.get(0).length+"");

							gestureValues.clear();


							meanX = computeMean(x);
							stdDevX = computeStdDev(x, meanX);
							//Log.d("Mean%%%%%> = " + meanX, " StdvX " + stdDevX + " len" + x.length);

							meanY = computeMean(y);
							stdDevY = computeStdDev(y, meanY);
						//	Log.d("Mean%%%%%> = " + meanY, " StdvX " + stdDevY);

							meanZ = computeMean(z);
							stdDevZ = computeStdDev(z, meanZ);
						//	Log.d("Mean%%%%%> = " + meanZ, " StdvX " + stdDevZ);


						//	Log.d("Normalized =", " " + normSignal.getValue(0, 1) + "-- RAW x " + xBlock[1] + "*" + normSignal.length());

					/*	for(int i=0; i<normSignal.length();i++) {
 						}
					*/    // time = System.currentTimeMillis();
							max = .0;
							for (double val : accBlock) {
								if (max < val) {
									max = val;
								}
							}

							fft.fft(re, im);
						fft.fft(Rre, Rim);
//Log.d("apex","fft"+Rre);

						for (int i = 0; i < re.length; i++) {
								double mag = Math.sqrt(re[i] * re[i] + im[i]
										* im[i]);
							Log.d("apex accfft"," "+mag);

							inst.setValue(i, mag);
								im[i] = .0; // Clear the field
////////////////////////////////////////////////////////
						}
						for (int i = 0; i < Rre.length; i++) {
							double mag1 = Math.sqrt(Rre[i] * Rre[i] + Rim[i]
									* Rim[i]);
							Log.d("apex fft"," "+mag1);

							inst.setValue(64+i, mag1);
							Rim[i] = .0; // Clear the field  /// 12th index pe last rotation fft value
						}
							// Append max after frequency component
							inst.setValue(Globals.ACCELEROMETER_BLOCK_CAPACITY+64, max);   //128th index pe max

							inst.setValue(129, meanX);
							inst.setValue(130,meanY);

							inst.setValue(131,meanZ);
							inst.setValue(132,stdDevX);
							inst.setValue(133,stdDevY);
							inst.setValue(134,stdDevZ);


							inst.setValue(mClassAttribute, mLabel);
						Log.d("apex inst", " " + inst);
									mDataset.add(inst);
						//	Log.i("new instance", inst + "");
							//Log.d("Classifiy "," &&&&&&-> "+cls.classifyInstance(inst));
						//	double v = cls.classifyInstance(inst);

							//sendMessageToUI((int) v);
							//z++;

						//	Log.i("speak ", " " + (int) v);


								}
				} catch (Exception e) {
					e.printStackTrace();
				}

			}}

		@Override
		protected void onCancelled() {

		//	Log.e("123", mDataset.size()+"");

			if (mServiceTaskType == Globals.SERVICE_TASK_TYPE_CLASSIFY) {
				super.onCancelled();
				return;
			}
			Log.i("in the loop","still in the loop cancelled");
			String toastDisp;

			if (mFeatureFile.exists()) {

				// merge existing and delete the old dataset
				DataSource source;
				try {
					// Create a datasource from mFeatureFile where
					// mFeatureFile = new File(getExternalFilesDir(null),
					// "features.arff");
					source = new DataSource(new FileInputStream(mFeatureFile));
					// Read the dataset set out of this datasource
					Instances oldDataset = source.getDataSet();
					oldDataset.setClassIndex(mDataset.numAttributes() - 1);
					// Sanity checking if the dataset format matches.
					if (!oldDataset.equalHeaders(mDataset)) {
						// Log.d(Globals.TAG,
						// oldDataset.equalHeadersMsg(mDataset));
						throw new Exception(
								"The two datasets have different headers:\n");
					}

					// Move all items over manually
					for (int i = 0; i < mDataset.size(); i++) {
						oldDataset.add(mDataset.get(i));
					}

					mDataset = oldDataset;
					// Delete the existing old file.
					mFeatureFile.delete();
					Log.i("delete","delete the file");
				} catch (Exception e) {
					e.printStackTrace();
				}
				toastDisp = getString(R.string.ui_sensor_service_toast_success_file_updated);

			} else {
				toastDisp = getString(R.string.ui_sensor_service_toast_success_file_created)   ;
			}
			Log.i("save","create saver here");
			// create new Arff file
			ArffSaver saver = new ArffSaver();
			// Set the data source of the file content
			saver.setInstances(mDataset);
			Log.e("1234", mDataset.size()+"");
			try {
				// Set the destination of the file.
				// mFeatureFile = new File(getExternalFilesDir(null),
				// "features.arff");
				saver.setFile(mFeatureFile);
				// Write into the file
				saver.writeBatch();
				Log.i("batch","write batch here");
				Toast.makeText(getApplicationContext(), toastDisp,
						Toast.LENGTH_SHORT).show();
			} catch (IOException e) {
				toastDisp = getString(R.string.ui_sensor_service_toast_error_file_saving_failed);
				e.printStackTrace();
			}

			Log.i("toast","toast here");
			super.onCancelled();
		}

	}

	private double[] gravity = new double[3];
	private double[] linear_acceleration = new double[3];
	private double rot ;
	private void getAccelero(SensorEvent event)
	{


		final double alpha= 0.8;
		gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0];
		gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1];
		gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2];

		linear_acceleration[0] = event.values[0] - gravity[0];
		linear_acceleration[1] = event.values[1] - gravity[1];
		linear_acceleration[2] = event.values[2] - gravity[2];
		this.actulAccelero = true;
	}

	private void getGyro(SensorEvent event)
	{
	    rot=event.values[0];
		this.acutalGyro = true;
	}

	public void onSensorChanged(SensorEvent event) {

//Log.d("apex","onsensor");
		switch (event.sensor.getType()) {
			case Sensor.TYPE_LINEAR_ACCELERATION:
		//		Log.d("apex","acc");


				getAccelero(event);


				break;
			case Sensor.TYPE_GYROSCOPE:
			//	Log.d("apex","gyro");

				getGyro(event);

				break;
		}

		if (((this.acutalGyro) && (this.actulAccelero) ) ) {
		//	Log.d("apex","total");

			this.acutalGyro = false;
			this.actulAccelero = false;
			Sample s=new Sample(linear_acceleration[0],linear_acceleration[1],linear_acceleration[2],rot);

			// Inserts the specified element into this queue if it is possible
			// to do so immediately without violating capacity restrictions,
			// returning true upon success and throwing an IllegalStateException
			// if no space is currently available. When using a
			// capacity-restricted queue, it is generally preferable to use
			// offer.
            linear_acceleration[0]=0.0;
			linear_acceleration[1]=0.0;
			linear_acceleration[2]=0.0;
			rot=0.0;


			try {
				mAccBuffer.add(s);

				//	Log.d("Gesture ###########> "," "+s.getX());
			} catch (IllegalStateException e) {

				// Exception happens when reach the capacity.
				// Doubling the buffer. ListBlockingQueue has no such issue,
				// But generally has worse performance
				ArrayBlockingQueue<Sample> newBuf = new ArrayBlockingQueue<Sample>(
						mAccBuffer.size() * 2);

				mAccBuffer.drainTo(newBuf);
				mAccBuffer = newBuf;
				mAccBuffer.add(s);
			//	Log.d("Gesture after catch #> ", " " + s.getX());

			}
		}
	}

	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

}
