package org.droidplanner.pebble;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.PebbleKit.PebbleDataReceiver;
import com.getpebble.android.kit.util.PebbleDictionary;
import com.o3dr.android.client.Drone;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.attribute.AttributeType;
import com.o3dr.services.android.lib.drone.property.Altitude;
import com.o3dr.services.android.lib.drone.property.Battery;
import com.o3dr.services.android.lib.drone.property.GuidedState;
import com.o3dr.services.android.lib.drone.property.Speed;
import com.o3dr.services.android.lib.drone.property.State;
import com.o3dr.services.android.lib.drone.property.VehicleMode;
import com.o3dr.services.android.lib.gcs.follow.FollowState;
import com.o3dr.services.android.lib.gcs.follow.FollowType;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class PebbleNotificationProvider{

	private static final int KEY_MODE = 0;
	private static final int KEY_FOLLOW_TYPE = 1;
	private static final int KEY_TELEM = 2;
	private static final int KEY_APP_VERSION = 3;

	private static final UUID DP_UUID = UUID.fromString("1de866f1-22fa-4add-ba55-e7722167a3b4");
	private static final String EXPECTED_APP_VERSION = "one";

    private final Context applicationContext;
    private final Drone dpApi;
    private final MainActivity mainActivity;

    long timeWhenLastTelemSent = System.currentTimeMillis();
    private PebbleDataReceiver datahandler;

    public PebbleNotificationProvider(Context context, MainActivity mainActivity, Drone dpApi) {
        this.dpApi = dpApi;
        applicationContext = context;
        this.mainActivity = mainActivity;
        PebbleKit.startAppOnPebble(applicationContext, DP_UUID);
        datahandler = new PebbleReceiverHandler(DP_UUID);
        PebbleKit.registerReceivedDataHandler(applicationContext, datahandler);
    }

    public void processData(Intent intent) {
        final String action = intent.getAction();
        if(AttributeEvent.STATE_CONNECTED.equals(action)){
            PebbleKit.startAppOnPebble(applicationContext, DP_UUID);
        }
        else if(AttributeEvent.STATE_VEHICLE_MODE.equals(action)
                || AttributeEvent.BATTERY_UPDATED.equals(action)
                ||AttributeEvent.SPEED_UPDATED.equals(action)){
            sendDataToWatchIfTimeHasElapsed(dpApi);
        }
        else if((AttributeEvent.FOLLOW_START.equals(action)
                || AttributeEvent.FOLLOW_STOP.equals(action))) {
            sendDataToWatchIfTimeHasElapsed(dpApi);

            FollowState followState = dpApi.getAttribute(AttributeType.FOLLOW_STATE);
            if(followState != null) {
                String eventLabel = null;
                switch (followState.getState()) {
                    case FollowState.STATE_START:
                    case FollowState.STATE_RUNNING:
                        eventLabel = "FollowMe enabled";
                        break;

                    case FollowState.STATE_END:
                        eventLabel = "FollowMe disabled";
                        break;

                    case FollowState.STATE_INVALID:
                        eventLabel = "FollowMe error: invalid state";
                        break;

                    case FollowState.STATE_DRONE_DISCONNECTED:
                        eventLabel = "FollowMe error: drone not connected";
                        break;

                    case FollowState.STATE_DRONE_NOT_ARMED:
                        eventLabel = "FollowMe error: drone not armed";
                        break;
                }

                if (eventLabel != null) {
                    Toast.makeText(applicationContext, eventLabel, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

	public void onTerminate() {
		if (datahandler != null) {
			applicationContext.unregisterReceiver(datahandler);
			datahandler = null;
		}
	}

	/**
	 * Calls sendDataToWatchNow if and only if the timeout of 500ms has elapsed
	 * since last call to prevent DOSing the pebble. If not, the packet will be
	 * dropped. If this packet is important (e.g. mode change), call
	 * sendDataToWatchNow directly.
	 * 
	 * @param drone
	 */
	public void sendDataToWatchIfTimeHasElapsed(Drone drone) {
		if (System.currentTimeMillis() - timeWhenLastTelemSent > 500) {
			sendDataToWatchNow(drone);
			timeWhenLastTelemSent = System.currentTimeMillis();
		}
	}

	/**
	 * Sends a full dictionary with updated information when called. If no
	 * pebble is present, the watchapp isn't installed, or the watchapp isn't
	 * running, nothing will happen.
	 * 
	 * @param drone
	 */
	public void sendDataToWatchNow(Drone drone) {
        final FollowState followState = drone.getAttribute(AttributeType.FOLLOW_STATE);
        final State droneState = drone.getAttribute(AttributeType.STATE);
        if(followState == null || droneState == null)
            return;

		PebbleDictionary data = new PebbleDictionary();

		VehicleMode mode = droneState.getVehicleMode();
        if(mode == null)
            return;

        final GuidedState guidedState = drone.getAttribute(AttributeType.GUIDED_STATE);
        String modeLabel = mode.getLabel();
		if (!droneState.isArmed())
			modeLabel = "Disarmed";
		else if (followState.isEnabled())
			modeLabel = "Follow";
		else if (guidedState.isIdle())
			modeLabel = "Paused";

		data.addString(KEY_MODE, modeLabel);

		FollowType type = followState.getMode();
		if (type != null) {
			data.addString(KEY_FOLLOW_TYPE, type.getTypeLabel());
		} else
			data.addString(KEY_FOLLOW_TYPE, "none");

        final Battery droneBattery = drone.getAttribute(AttributeType.BATTERY);
        Double battVoltage = droneBattery.getBatteryVoltage();
        if(battVoltage != null)
            battVoltage = 0.0;
		String bat = "Bat:" + Double.toString(roundToOneDecimal(battVoltage))	+ "V";

        final Speed droneSpeed = drone.getAttribute(AttributeType.SPEED);
		String speed = "Speed: " + Double.toString(roundToOneDecimal(droneSpeed.getAirSpeed()));

        final Altitude droneAltitude = drone.getAttribute(AttributeType.ALTITUDE);
		String altitude = "Alt: "	+ Double.toString(roundToOneDecimal(droneAltitude.getAltitude()));
		String telem = bat + "\n" + altitude + "\n" + speed;
		data.addString(KEY_TELEM, telem);

		data.addString(KEY_APP_VERSION, EXPECTED_APP_VERSION);

		PebbleKit.sendDataToPebble(applicationContext, DP_UUID, data);
	}

	private double roundToOneDecimal(double value) {
		return (double) Math.round(value * 10) / 10;
	}

	public class PebbleReceiverHandler extends PebbleDataReceiver {

		private static final int KEY_PEBBLE_REQUEST = 100;
		private static final int KEY_REQUEST_MODE_FOLLOW = 101;
		private static final int KEY_REQUEST_CYCLE_FOLLOW_TYPE = 102;
		private static final int KEY_REQUEST_PAUSE = 103;
		private static final int KEY_REQUEST_MODE_RTL = 104;

		protected PebbleReceiverHandler(UUID id) {
			super(id);
		}

		@Override
		public void receiveData(Context context, int transactionId, PebbleDictionary data) {
			FollowState followMe = dpApi.getAttribute(AttributeType.FOLLOW_STATE);
            if(followMe == null)
                return ;
			PebbleKit.sendAckToPebble(applicationContext, transactionId);
            mainActivity.connect3DRServices();

			int request = (data.getInteger(KEY_PEBBLE_REQUEST).intValue());
			switch (request) {

			case KEY_REQUEST_MODE_FOLLOW:
                if(followMe.isEnabled()){
                    dpApi.disableFollowMe();
                }
                else {
                    dpApi.enableFollowMe(followMe.getMode());
                }
				break;

			case KEY_REQUEST_CYCLE_FOLLOW_TYPE:
                List<FollowType> followTypes = Arrays.asList(FollowType.values());
                int currentTypeIndex = followTypes.indexOf(followMe.getMode());
                int nextTypeIndex = currentTypeIndex++ % followTypes.size();
                dpApi.enableFollowMe(followTypes.get(nextTypeIndex));
				break;

			case KEY_REQUEST_PAUSE:
				dpApi.pauseAtCurrentLocation();
				break;

			case KEY_REQUEST_MODE_RTL:
				dpApi.changeVehicleMode(VehicleMode.COPTER_RTL);
				break;
			}
		}
	}
}
