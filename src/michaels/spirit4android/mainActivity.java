package michaels.spirit4android;

import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import michaels.spirit4android.FHSSchedule.Event;

import org.json.JSONObject;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;


public class mainActivity extends Activity {
	public static String USERAGENT;
	public static SharedPreferences saveFile;
	public static JSONObject newsObject = null;
	//public static String preJSON = "";
	public static SQLiteDatabase database = null;
	public static long last_news_update = 0;
	boolean pause;
	boolean showCompletePlan;
	static FHSSchedule schedule;
	static TimeStreamView tsv;
	static NewsAdapter news_list_adapter;
	
	public void onStart(){
		/*
		 * onStart - entrypoint of this app. Android API needs the call of
		 * "super.onStart()". 
		 */
		super.onStart();
		
		// Setting Useragent: "Spirit4Android v<versionName> (disp <displayWidth>*<displayHeight>)"
		try {
			DisplayMetrics dm = new DisplayMetrics();
			this.getWindowManager().getDefaultDisplay().getMetrics(dm);
			PackageInfo pi = this.getPackageManager().getPackageInfo(this.getPackageName(), 0);
			USERAGENT = "Spirit4Android v"+pi.versionName+" (display-size "+dm.widthPixels+"*"+dm.heightPixels+")";
		} catch(Exception e){
			USERAGENT = "Spirit4Android (version or display-metrics not available)";
		}
		
		// Loading Preferences
		if(saveFile == null)
			saveFile = this.getSharedPreferences("s4apref",MODE_WORLD_READABLE);
		
		// Checking, if a new semester has started and if yes, showing a dialog 
		// reminding user to change the semester
		if(saveFile.getInt("letzterSemesterWechsel", Calendar.getInstance().get(Calendar.MONTH)) != Calendar.getInstance().get(Calendar.MONTH) && (Calendar.getInstance().get(Calendar.MONTH) == Calendar.OCTOBER || Calendar.getInstance().get(Calendar.MONTH) == Calendar.APRIL)){
			AlertDialog.Builder ab = new AlertDialog.Builder(this);
			ab.setTitle(R.string.LANG_SEMESTERCHANGE)
				.setMessage(R.string.LANG_SEMESTERCHANGELONG)
				.setPositiveButton(R.string.LANG_SCHEDULESETTINGS, new OnClickListener(){

					public void onClick(DialogInterface arg0, int arg1) {
						Intent intent = new Intent(mainActivity.this,settingsActivity.class);
						mainActivity.this.startActivity(intent);
						Editor e = saveFile.edit();
						e.putInt("letzterSemesterWechsel", Calendar.getInstance().get(Calendar.MONTH));
						e.commit();
					}
					
				});
			ab.create().show();
		}
		
		// First initialization: setting last-semester-change for check above.
		if(!saveFile.contains("letzterSemesterWechsel")){
			Editor e = saveFile.edit();
			e.putInt("letzterSemesterWechsel", Calendar.getInstance().get(Calendar.MONTH));
			e.commit();
		}
		
		// Load Database
		openDB(this);
				
		// Set last_news_update for dispRefresh
		last_news_update = saveFile.getLong("lastUpdate", 0);
		
		// Setting and Loading UI
		this.setContentView(R.layout.main);
		((TextView)this.findViewById(R.id.news_week)).setText(this.getString(R.string.LANG_WEEK_SHORT)+" "+Calendar.getInstance().get(Calendar.WEEK_OF_YEAR));
		
		// Prepare the ListAdapter for the News-List
		news_list_adapter = new NewsAdapter(this,database);
		((ListView) this.findViewById(R.id.newsListe)).setAdapter(news_list_adapter);
		this.refreshNews();
		
		// Starting the news-updater delayed (for better performance at start)
		Handler updateHandler = new Handler();
		updateHandler.postDelayed(new Updater(),10000);
	}
	
	public void onPause(){
		/*
		 * onPause - called when leaving Activity. Android API needs the 
		 * "super.onPause()"-call.
		 */
		
		// UI updating-handler will be paused.
		pause = true;
		
		super.onPause();
	}
	
	public void onResume(){
		/*
		 * onResume - called when reload the Activity. Android API needs the 
		 * "super.onResume()"-call.
		 */
		
		// refresh schedule
		schedule = new FHSSchedule(this);
		
		// resume UI-updater
		pause = false;
		Handler h = new Handler();
		h.post(new dispRefresh());
		super.onResume();
		
		// (re-)set AlarmManager for using Alarm
		setAlarm(this,false);
	}
	
	public boolean onCreateOptionsMenu(Menu m){
		this.getMenuInflater().inflate(R.menu.main_menu, m);
		return true;
	}
	
	public boolean onOptionsItemSelected(MenuItem mi){
		if(mi.getItemId() == R.id.settings){ // Einstellungen
			Intent EinstellungenStarten = new Intent(this,settingsActivity.class);
			this.startActivity(EinstellungenStarten);
			return true;
		} else if(mi.getItemId() == R.id.update){ // News-Update
			Editor e = saveFile.edit();
			e.putLong("lastUpdate", 0);
			e.commit();
			Toast.makeText(this, R.string.LANG_UPDATING, Toast.LENGTH_LONG).show();
			Handler updateHandler = new Handler();
			updateHandler.post(new Updater());
			return true;
		} else if(mi.getItemId() == R.id.about){ // Über
			Intent about_intent = new Intent(this,about.class);
			this.startActivity(about_intent);
			return true;
		} else if(mi.getItemId() == R.id.lastUpdate){ //letztes Update
			long lastUpdate = saveFile.getLong("lastUpdate", 0);
			Toast.makeText(this, getString(R.string.LANG_LASTSUCCESSFULLUPDATE)+
					(lastUpdate == 0 ? getString(R.string.LANG_NEVER) : DateFormat.getDateTimeInstance().format(new Date(lastUpdate))), 
					Toast.LENGTH_LONG).show();
		}
		return super.onOptionsItemSelected(mi);
	}
	
	public static void openDB(Context c){
		/*
		 * openDB - Loads or creates Database (in db are for example schedules,
		 * news and groups saved.
		 * Context is needed because SQLite-Databases can only be opened through
		 * a Context.
		 */
		if(database == null)
			database = c.openOrCreateDatabase("Spirit4Android", Context.MODE_WORLD_READABLE, null);
		database.execSQL("CREATE TABLE IF NOT EXISTS news (id INTEGER NOT NULL UNIQUE, title TEXT, author TEXT, receivers TEXT, date INTEGER, content TEXT)");
		database.execSQL("CREATE TABLE IF NOT EXISTS schedule (time INTEGER NOT NULL, length INTEGER, title TEXT, docent TEXT, room TEXT, week INTEGER, type INTEGER, egroup INTEGER)");
		database.execSQL("CREATE TABLE IF NOT EXISTS groups (title TEXT UNIQUE, type INTEGER, egroup INTEGER)");
		database.execSQL("CREATE TABLE IF NOT EXISTS sparetime (desc TEXT UNNIQUE, start INTEGER, stop INTEGER)");
		if(saveFile == null)
			saveFile = c.getSharedPreferences("s4apref",MODE_WORLD_READABLE);
	}

	public static void setAlarm(Context c, boolean force){
		/*
		 * setAlarm - Static Function to set alarm. Context is needed for 
		 * launching the AlarmActivity and getting the AlarmManager-Service.
		 * Set force to true if you want to skip the double-set prevention.
		 */
		
		// static function so it is possible that schedule is null.
		if(schedule == null)
			schedule = new FHSSchedule(c);
		
		long alarm_time_before_event = saveFile.getLong("alarmtimeBeforeEvent", -1);
		if(alarm_time_before_event > -1 && schedule.length() > 0){
			PendingIntent pending_intent = PendingIntent.getActivity(c, 0, new Intent(c,AlarmActivity.class), PendingIntent.FLAG_ONE_SHOT);
			AlarmManager alarmator = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
			
			Calendar current_day = Calendar.getInstance();
			long actual_time = current_day.get(Calendar.DAY_OF_WEEK)*24*60*60 +
					current_day.get(Calendar.HOUR_OF_DAY)*60*60 +
					current_day.get(Calendar.MINUTE)*60+
					current_day.get(Calendar.SECOND);
			current_day.set(Calendar.HOUR_OF_DAY, 0);
			current_day.set(Calendar.MINUTE, 0);
			current_day.set(Calendar.SECOND, 0);
			Event[] days_events = schedule.getEventsAtDay(current_day);
			
			// if first event of today is before now, add a day and update events-array 
			if(days_events.length > 0 && days_events[0].time < actual_time){
				current_day.add(Calendar.DAY_OF_MONTH, 1);
				days_events = schedule.getEventsAtDay(current_day);
			}
			
			// if the days has no events or the first event of the day is before now, jump to next day.
			while(days_events.length == 0 || schedule.getNextCalendar(days_events[0]).before(Calendar.getInstance())){
				current_day.add(Calendar.DAY_OF_MONTH, 1);
				days_events = schedule.getEventsAtDay(current_day);
			}
			
			// calculating time of the next alarm, setting alarm and activate double-set prevention
			long time_to_set = schedule.getNextCalendar(days_events[0]).getTimeInMillis()-alarm_time_before_event;	
			if(time_to_set != saveFile.getLong("last_alarm_set", 0) || force){
				alarmator.set(AlarmManager.RTC_WAKEUP, schedule.getNextCalendar(days_events[0]).getTimeInMillis()-alarm_time_before_event, pending_intent);
				
				// Preventing double setting of alarm at same time. Use parameter force, to set it without prevention.
				Editor e = saveFile.edit();
				e.putLong("last_alarm_set", time_to_set);
				e.commit();
			}
		}
	}
	
	public static String MD5(String str){
		/*
		 * MD5 - Static function for calculating MD5-Hash of a given String.
		 * Returns Hexadecimal-Representation of the hash as String.
		 */
		try {
			String rtn = "";
			MessageDigest md = MessageDigest.getInstance("MD5");
			md.reset();
			byte[] resBytes = md.digest(str.getBytes());
			for(byte b:resBytes){
				rtn += String.format("%02x", (((int)b) & 0xFF));
			}
			return rtn;
		} catch (Exception e) {
			Log.e("MD5", e.getClass().getName()+": "+e.getMessage());
		}
		return "";
	}
	
	public void refreshNews(){
		/*
		 * refreshNews - Refreshes the ListView for displaying updated news from
		 * the Database.
		 */
		ListView news_list = (ListView) this.findViewById(R.id.newsListe);
		news_list_adapter.updateData();
		news_list.setOnItemClickListener(new OnItemClickListener() {

			public void onItemClick(AdapterView<?> arg0, View arg1, int arg2, long arg3) {
				newsActivity.id = (int) news_list_adapter.getItemId(arg2);
				Intent intent = new Intent(mainActivity.this,newsActivity.class);
				mainActivity.this.startActivity(intent);
			}
		});
		
	}
	
	class dispRefresh implements Runnable {
		public void run() {
			/*
			 * dispRefresh.run - a function called through a Handler. Refreshes
			 * the displayed Views. For example, the countdown and the schedule
			 * view.
			 */
			
			// Don't refresh if activity is not visible!
			if(!pause){
				if(mainActivity.schedule.length() == 0){
					// We've no schedule to show. Show a message to download a
					// schedule.
					((LinearLayout)mainActivity.this.findViewById(R.id.main_plan)).setVisibility(View.VISIBLE);
					((LinearLayout)mainActivity.this.findViewById(R.id.main_plan_alternative)).setVisibility(View.GONE);
					((TextView)mainActivity.this.findViewById(R.id.countdown)).setVisibility(View.GONE);
					((TextView)mainActivity.this.findViewById(R.id.nächstesEvent)).setText(R.string.LANG_NOSCHEDULELOADED);
				} else {
					// OK, a schedule is available.
					TextView nevent = (TextView)mainActivity.this.findViewById(R.id.nächstesEvent);
					((LinearLayout)mainActivity.this.findViewById(R.id.main_plan_alternative)).setVisibility(View.VISIBLE);
					if(tsv == null || tsv.switchDayAutomatically){
						// we are currently in the mode which shows a countdown
						// and the next events from now
						TextView countdown = (TextView)mainActivity.this.findViewById(R.id.countdown);
						Event c = schedule.getNextEvent();
						Calendar ccalendar = schedule.getCalendar(c, false);
						long zeitDifferenz = ccalendar.getTimeInMillis()- new GregorianCalendar().getTimeInMillis();
						long stunden  = (zeitDifferenz / (3600*1000));
						long minuten  = (zeitDifferenz / 60000) - (stunden * 60);
						long sekunden = (zeitDifferenz / 1000) - (stunden*3600+minuten*60);
						GregorianCalendar bzt = (GregorianCalendar) ccalendar.clone();
						bzt.set(Calendar.HOUR_OF_DAY, 0);
						bzt.set(Calendar.MINUTE, 0);
						long tage = (bzt.getTimeInMillis()-new GregorianCalendar().getTimeInMillis())/(24*60*60*1000) +1;
						countdown.setText(stunden>23 ? 
								String.format(
										getString( tage==1 ? R.string.LANG_COUNTDOWNDAY : R.string.LANG_COUNTDOWNDAYS),
										tage, 
										DateFormat.getTimeInstance(DateFormat.SHORT).format(schedule.getNextCalendar(schedule.getNextEvent()).getTime())) : 
								String.format("%02d:%02d:%02d", stunden,minuten,sekunden));
						countdown.setVisibility(View.VISIBLE);
						nevent.setText(String.format(getString(R.string.LANG_NEXTEVENTIN), getString(c.type == FHSSchedule.EVENT_LECTURE ? R.string.LANG_LECTURE : R.string.LANG_EXERCISE)));
						if(tsv == null || tsv.schedule != schedule){
							tsv = new TimeStreamView(mainActivity.this,schedule);
							((LinearLayout) mainActivity.this.findViewById(R.id.main_plan_alternative)).removeAllViews();
							((LinearLayout) mainActivity.this.findViewById(R.id.main_plan_alternative)).addView(tsv);
						}
						nevent.setClickable(false);
						nevent.setTextColor(0xffffffff);
					} else {
						// we are currently in the mode which shows a day set by
						// the user
						((TextView)mainActivity.this.findViewById(R.id.countdown)).setVisibility(View.GONE);
						nevent.setTextColor(0xffcccccc);
						nevent.setText(R.string.LANG_CLICKTOGETBACK);
						nevent.setOnClickListener(new View.OnClickListener(){

							public void onClick(View v) {
								tsv.switchDayAutomatically = true;
							}
							
						});
						nevent.setClickable(true);
					}
					tsv.invalidate();
				}
			}
			if(!mainActivity.this.pause){
				// Activity is in foreground
				if(mainActivity.saveFile.getLong("lastUpdate", 0) != mainActivity.last_news_update) // News have been updated
					mainActivity.this.refreshNews();
				// next update in 300ms (experiences show that this is the best
				// rate)
				Handler h = new Handler();
				h.removeCallbacks(this);
				h.postDelayed(this, 300);
			}
				
			
			//preJSON = mainActivity.saveFile.getString("newsJSON", "")+mainActivity.saveFile.getString("scheduleJSON", "");
		}
	}
}
