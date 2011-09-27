package org.ohmage.activity;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;

import org.ohmage.R;
import org.ohmage.controls.DateFilterControl;
import org.ohmage.controls.DateFilterControl.DateFilterChangeListener;
import org.ohmage.controls.FilterControl;
import org.ohmage.controls.FilterControl.FilterChangeListener;
import org.ohmage.db.DbContract.Campaigns;
import org.ohmage.db.DbContract.Responses;
import org.ohmage.db.DbContract.Surveys;
import org.ohmage.db.DbHelper;
import org.ohmage.db.Models.Campaign;
import org.ohmage.feedback.visualization.MapOverlayItem;
import org.ohmage.feedback.visualization.MapViewItemizedOverlay;
import org.ohmage.feedback.visualization.ResponseHistory;
import org.ohmage.prompt.Prompt;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.maps.GeoPoint;
import com.google.android.maps.MapController;
import com.google.android.maps.MapView;
import com.google.android.maps.Overlay;

public class RHMapViewActivity extends ResponseHistory {

	static final String TAG = "MapActivityLog"; 
	private MapViewItemizedOverlay mItemizedoverlay = null;
	private MapView mMapView;
	private MapController mControl;
	private String mCampaignUrn;
	private String mSurveyId;
	private List<Prompt> mPrompts;
	private FilterControl mCampaignFilter;
	private FilterControl mSurveyFilter;
	private DateFilterControl mDateFilter;
	private Button mMapPinNext;
	private Button mMapPinPrevious;
	private Button mMapZoomIn;
	private Button mMapZoomOut;
	private TextView mMapPinIdxButton;
	private int mPinIndex;
	
	@Override
	protected boolean isRouteDisplayed() {
		return false;
	}
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
	    super.onCreate(savedInstanceState);
	    setTitle("Response Map Summary");
	    setContentView(R.layout.mapview);
	    
	    mMapView = (MapView) findViewById(R.id.mapview);
	    mMapView.setBuiltInZoomControls(false);
	    
		mMapPinNext = (Button) mMapView.getRootView().findViewById(R.id.map_pin_next);
		mMapPinPrevious = (Button) mMapView.getRootView().findViewById(R.id.map_pin_previous);
		mMapPinIdxButton = (TextView) mMapView.getRootView().findViewById(R.id.map_pin_index);
	    
		mMapZoomIn = (Button) mMapView.getRootView().findViewById(R.id.map_zoom_in);
		mMapZoomOut = (Button) mMapView.getRootView().findViewById(R.id.map_zoom_out);
		
		mMapZoomIn.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				mMapView.getController().zoomIn();
			}
		});
		
		mMapZoomOut.setOnClickListener(new OnClickListener(){
			
			@Override
			public void onClick(View v) {
				mMapView.getController().zoomOut();
			}
		});

	    mControl = mMapView.getController();
	    mControl.setZoom(11);

	    //Init the map center to current location
	    setMapCenterToCurrentLocation();

	    setupFilters();
	    displayItemsOnMap();	
	}
	
	public void displayItemsOnMap(){
		
		//Clear current overlay items
		mMapView.getOverlays().clear();
		mMapView.removeAllViews();
		
		//Get the currently selected CampaignUrn and SurveyID
	    String curSurveyValue = mSurveyFilter.getValue();
		mCampaignUrn = curSurveyValue.substring(0, curSurveyValue.lastIndexOf(":"));
		mSurveyId = curSurveyValue.substring(curSurveyValue.lastIndexOf(":")+1, curSurveyValue.length());

		//Retrieve data from CP
	    ContentResolver cr = this.getContentResolver();
		
	    Uri queryUri;
		if(mCampaignUrn.equals("all")){
			if(mSurveyId.equals("all")){
				queryUri = Responses.CONTENT_URI;
			}
			else{
				queryUri = Campaigns.buildResponsesUri(mCampaignUrn, mSurveyId);
			}
		}
		else{
			if(mSurveyId.equals("all")){
				queryUri = Campaigns.buildResponsesUri(mCampaignUrn);
			}
			else{
				queryUri = Campaigns.buildResponsesUri(mCampaignUrn, mSurveyId);
			}
		}
		

		Calendar cal = mDateFilter.getValue();
		GregorianCalendar greCalStart = new GregorianCalendar(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), 1);
		GregorianCalendar greCalEnd = new GregorianCalendar(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.getActualMaximum(Calendar.DAY_OF_MONTH));
		
		String[] projection = {
				DbHelper.Tables.RESPONSES+"."+Responses._ID,
				DbHelper.Tables.RESPONSES+"."+Responses.RESPONSE_LOCATION_LATITUDE, 
				DbHelper.Tables.RESPONSES+"."+Responses.RESPONSE_LOCATION_LONGITUDE,
				DbHelper.Tables.RESPONSES+"."+Responses.SURVEY_ID, 
				DbHelper.Tables.RESPONSES+"."+Responses.CAMPAIGN_URN, 
				DbHelper.Tables.RESPONSES+"."+Responses.RESPONSE_DATE 
				};
		
		String selection = 
				Responses.RESPONSE_TIME + " > " + greCalStart.getTime().getTime() +
				" AND " + 
				Responses.RESPONSE_TIME + " < " + greCalEnd.getTime().getTime() + 
				" AND " +
				Responses.RESPONSE_LOCATION_STATUS + "=" + "'valid'";
		
	    Cursor cursor = cr.query(queryUri, projection, selection, null, null);

	    //Add overlays to the map
	    List<Overlay> mapOverlays = mMapView.getOverlays();
	    Drawable drawable = this.getResources().getDrawable(R.drawable.darkgreen_marker_a);
	    mItemizedoverlay= new MapViewItemizedOverlay(drawable, mMapView);


	    for(cursor.moveToFirst();!cursor.isAfterLast();cursor.moveToNext()){
		    Double lat = cursor.getDouble(cursor.getColumnIndex(Responses.RESPONSE_LOCATION_LATITUDE));
		    Double lon = cursor.getDouble(cursor.getColumnIndex(Responses.RESPONSE_LOCATION_LONGITUDE));
		    GeoPoint point = new GeoPoint((int)(lat.doubleValue()*1e6), (int)(lon.doubleValue()*1e6));
		    String title = cursor.getString(cursor.getColumnIndex(Responses.SURVEY_ID));
		    String text = cursor.getString(cursor.getColumnIndex(Responses.CAMPAIGN_URN)) + "\n" + 
		    cursor.getString(cursor.getColumnIndex(Responses.RESPONSE_DATE));
		    String id = cursor.getString(cursor.getColumnIndex(Responses._ID));
		    
			MapOverlayItem overlayItem = new MapOverlayItem(point, title, text, id);
			mItemizedoverlay.setBalloonBottomOffset(40);
			mItemizedoverlay.addOverlay(overlayItem);
	    }
	    cursor.close();

	    if(mItemizedoverlay.size() > 0){
		    mapOverlays.add(mItemizedoverlay);
		    
		    int maxLatitude = mItemizedoverlay.getMaxLatitude();
		    int minLatitude = mItemizedoverlay.getMinLatitude();
		    
		    int maxLongitude = mItemizedoverlay.getMaxLongitude();
		    int minLongitude = mItemizedoverlay.getMinLongitude();
		    
		    mControl.animateTo(new GeoPoint((maxLatitude+minLatitude)/2, (maxLongitude+minLongitude)/2));
		    mControl.zoomToSpan(Math.abs(maxLatitude-minLatitude), Math.abs(maxLongitude-minLongitude));

	    }

	    //Set Map Pin Navigators.
		mPinIndex = -1;
		if(mMapPinIdxButton != null){
			mMapPinIdxButton.setText("");
			//mMapPinIdxButton.setText(""+ ((mPinIndex==-1)?0:1) + "/" + ((mPinIndex==-1)?0:mItemizedoverlay.size()));
		}
		
		if(mMapPinNext != null){
			mMapPinNext.setOnClickListener(new OnClickListener() {
				
				@Override
				public void onClick(View v) {
					int overlayListSize = mItemizedoverlay.size();
					if(overlayListSize > 0){
						 if(mPinIndex < (overlayListSize-1)){
							mPinIndex++;
							mItemizedoverlay.onTap(mPinIndex % overlayListSize);
							mMapPinIdxButton.setText(""+(mPinIndex+1)+"/"+overlayListSize);
						 }
					}
				}
			});			
		}
		
		if(mMapPinPrevious != null){
			mMapPinPrevious.setOnClickListener(new OnClickListener() {
				
				@Override
				public void onClick(View v) {
					int overlayListSize = mItemizedoverlay.size();
					if(overlayListSize > 0){
						if(mPinIndex > 0){
							mPinIndex--;
							mItemizedoverlay.onTap(mPinIndex % overlayListSize);
							mMapPinIdxButton.setText(""+(mPinIndex+1)+"/"+overlayListSize);	
						}
					}
				}
			});			
		}
		
	}
	
	public void setupFilters(){
		//Set filters
		mDateFilter = (DateFilterControl)findViewById(R.id.date_filter);
		mCampaignFilter = (FilterControl)findViewById(R.id.campaign_filter);
		mSurveyFilter = (FilterControl)findViewById(R.id.survey_filter);
	
		final ContentResolver cr = getContentResolver();
		mCampaignFilter.setOnChangeListener(new FilterChangeListener() {
			@Override
			public void onFilterChanged(boolean selfChange, String curCampaignValue) {
				Cursor surveyCursor;
				
				String[] projection = {Surveys.SURVEY_TITLE, Surveys.CAMPAIGN_URN, Surveys.SURVEY_ID};
				
				//Create Cursor
				if(curCampaignValue.equals("all")){
					surveyCursor = cr.query(Surveys.CONTENT_URI, projection, null, null, Surveys.SURVEY_TITLE);
				}
				else{
					surveyCursor = cr.query(Campaigns.buildSurveysUri(curCampaignValue), projection, null, null, null);
				}
	
				//Update SurveyFilter
				//Concatenate Campain_URN and Survey_ID with a colon for survey filer values,
				//in order to handle 'All Campaign' case.
				mSurveyFilter.clearAll();
				for(surveyCursor.moveToFirst();!surveyCursor.isAfterLast();surveyCursor.moveToNext()){
					mSurveyFilter.add(new Pair<String, String>(
							surveyCursor.getString(surveyCursor.getColumnIndex(Surveys.SURVEY_TITLE)),
							surveyCursor.getString(surveyCursor.getColumnIndex(Surveys.CAMPAIGN_URN)) + 
							":" +
							surveyCursor.getString(surveyCursor.getColumnIndex(Surveys.SURVEY_ID))
							));
				}
				mSurveyFilter.add(0, new Pair<String, String>("All Surveys", mCampaignFilter.getValue() + ":" + "all"));
				surveyCursor.close();
				
				displayItemsOnMap();
			}
		});
	
		mSurveyFilter.setOnChangeListener(new FilterChangeListener() {
			@Override
			public void onFilterChanged(boolean selfChange, String curValue) {
				displayItemsOnMap();
			}
		});
		
		mDateFilter.setOnChangeListener(new DateFilterChangeListener() {
			
			@Override
			public void onFilterChanged(Calendar curValue) {
				displayItemsOnMap();
			}
		});
		
		String select = Campaigns.CAMPAIGN_STATUS + "=" + Campaign.STATUS_READY;
		String[] projection = {Campaigns.CAMPAIGN_NAME, Campaigns.CAMPAIGN_URN};

		Cursor campaigns = cr.query(Campaigns.CONTENT_URI, projection, select, null, null);
		mCampaignFilter.populate(campaigns, Campaigns.CAMPAIGN_NAME, Campaigns.CAMPAIGN_URN);
		mCampaignFilter.add(0, new Pair<String, String>("All Campaigns", "all"));	
	}
	
	@Override
	protected void onPause(){
		super.onPause();
		RHTabHost.setCampaignFilterIndex(mCampaignFilter.getIndex());
		RHTabHost.setSurveyFilterIndex(mSurveyFilter.getIndex());
		RHTabHost.setDateFilterValue(mDateFilter.getValue());
	}
	
	@Override
	protected void onResume(){
		super.onResume();
		mCampaignFilter.setIndex(RHTabHost.getCampaignFilterIndex());
		mSurveyFilter.setIndex(RHTabHost.getSurveyFilterIndex());
		mDateFilter.setDate(RHTabHost.getDateFilterValue());
	}
		
	private void setMapCenterToCurrentLocation(){
	    //Set MapCenter to current location
	    LocationManager locMan = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
	    String provider = locMan.getBestProvider(new Criteria(), true);
	    Location currentLocation = locMan.getLastKnownLocation(provider);
	    
	    GeoPoint point = new GeoPoint(34065009, -118443413);
	    if(currentLocation != null) //If location is not available, then set the map center to UCLA
	    	point = new GeoPoint((int)(currentLocation.getLatitude()*1e6), (int)(currentLocation.getLongitude()*1e6));	    	
	    mControl.setCenter(point);		
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu){
		super.onCreateOptionsMenu(menu);
		menu.add(0,1,0,"Map");
		menu.add(0,2,0,"Satellite");
		return true;
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		switch (item.getItemId()){
		case 1:
			mMapView.setSatellite(false);
			return true;
		case 2:
			mMapView.setSatellite(true);
			return true;
		}
		return false;
	}
}
