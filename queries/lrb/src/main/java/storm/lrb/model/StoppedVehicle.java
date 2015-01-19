package storm.lrb.model;

import java.io.Serializable;

import org.apache.log4j.Logger;


/**
 * Helper class to count stops of a vehicle. 
 */
public class StoppedVehicle implements Serializable {
	
	private static final Logger LOG = Logger
			.getLogger(StoppedVehicle.class);
	
	private static final long serialVersionUID = 1L;
	private int vid;
	private int cnt = 0;
	private int time;
	private PosReport firstReport;
	private int position;
	
	//for serialization only
	public StoppedVehicle() {
		this.time = -1;
		this.vid = -1;
		this.firstReport = null;
		this.position = -1;
		
	}
	
	public StoppedVehicle(PosReport report) {
		this.vid = report.getVid();
		this.time = report.getTime();
		this.firstReport = report;	
		this.position = report.getPos();
	}

	public int getPosition(){
		return position;
	}

	/**
	 * checks if still stopped and records the stop and returns the number of
	 * how many consecutive stops this car has reported
	 * 
	 * @param curReport
	 * @return the number of consecutives stops this vehicle reported
	 */
	public int recordStop(PosReport curReport) {
		if(!stillStopped(curReport)){
			cnt = 1;
			firstReport = curReport;
			this.time = curReport.getTime();
			this.firstReport = curReport;	
			this.position = curReport.getPos();
		}
		else
			cnt++;
		
		return cnt;
	}

	/**
	 * check if new position report of car indicates that the car is still stopped
	 * @param pos new position report
	 * @return true if 
	 */
	protected boolean stillStopped(PosReport pos) {

		if (!(pos instanceof PosReport)) {
			return false;
		}
		if(pos.getTime()==firstReport.getTime()){
			//LOG.info("identischer posreport");
			return true;
		}
		else{
			return (pos.getXway().equals(firstReport.getXway()) && pos.getDir().equals(firstReport.getDir()))
				&& pos.getPos().equals(firstReport.getPos()) && pos.getTime() <= ((30*cnt)+firstReport.getTime());
		}
	}


	public final int getVid() {
		return vid;
	}

	@Override
	public String toString() {
		return "StoppedVehicle [vid=" + vid + ", cnt=" + cnt + ", time=" + time
				+ ", firstReport=" + firstReport + "]";
	}

	
}