package ca.ubc.cpsc210.nextbus;

import java.util.ArrayList;
import java.util.List;

import org.osmdroid.DefaultResourceProxyImpl;
import org.osmdroid.ResourceProxy;
import org.osmdroid.api.IMapController;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.ItemizedIconOverlay.OnItemGestureListener;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.OverlayItem;
import org.osmdroid.views.overlay.OverlayItem.HotspotPlace;
import org.osmdroid.views.overlay.OverlayManager;
import org.osmdroid.views.overlay.PathOverlay;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import ca.ubc.cpsc210.exception.TranslinkException;
import ca.ubc.cpsc210.nextbus.model.BusLocation;
import ca.ubc.cpsc210.nextbus.model.BusRoute;
import ca.ubc.cpsc210.nextbus.model.BusStop;
import ca.ubc.cpsc210.nextbus.translink.ITranslinkService;
import ca.ubc.cpsc210.nextbus.translink.TranslinkService;
import ca.ubc.cpsc210.nextbus.util.LatLon;
import ca.ubc.cpsc210.nextbus.util.Segment;
import ca.ubc.cpsc210.nextbus.util.TextOverlay;

/**
 * Fragment holding the map in the UI.
 */
public class MapDisplayFragment extends Fragment {

	/**
	 * Log tag for LogCat messages
	 */
	private final static String LOG_TAG = "MapDisplayFragment";

	/**
	 * Location of Nelson & Granville, downtown Vancouver
	 */
	private final static GeoPoint NELSON_GRANVILLE 
	= new GeoPoint(49.279285, -123.123007);

	/**
	 * Size of border on map view around area that contains buses and bus stop
	 * in dimensions of latitude * 1E6 (or lon * 1E6)
	 */
	private final static int BORDER = 500;

	/**
	 * Overlay for POI markers.
	 */
	private ItemizedIconOverlay<OverlayItem> busLocnOverlay;

	/**
	 * Overlay for bus stop location
	 */
	private ItemizedIconOverlay<OverlayItem> busStopLocationOverlay;

	/**
	 * Overlay for legend
	 */
	private TextOverlay legendOverlay;

	/**
	 * Overlays for displaying bus route - one PathOverlay for each segment of route
	 */
	private List<PathOverlay> routeOverlays;

	/**
	 * View that shows the map
	 */
	private MapView mapView;

	/**
	 * Selected bus stop
	 */
	private BusStop selectedStop;

	/**
	 * Wraps Translink web service
	 */
	private ITranslinkService tlService;

	/**
	 * Map controller for zooming in/out, centering
	 */
	private IMapController mapController;

	/**
	 * True if and only if map should zoom to fit displayed route.
	 */
	private boolean zoomToFit;

	/**
	 * Overlay item corresponding to bus selected by user
	 */
	private OverlayItem selectedBus;

	/**
	 * Index corresponding to the currently selected bus
	 */
	private int index1 = -1; 

	/**
	 * Vehicle number corresponding to the currently selected bus
	 */
	private String selectedVehicleNo = null;




	/**
	 * Set up Translink service and location listener
	 */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Log.d(LOG_TAG, "onActivityCreated");

		setHasOptionsMenu(true);

		tlService = new TranslinkService(getActivity());
		routeOverlays = new ArrayList<PathOverlay>();

		Log.d(LOG_TAG, "Stop number for mapping: " + (selectedStop == null ? "not set" : selectedStop.getStopNum()));
	}

	/**
	 * Set up map view with overlays for buses, selected bus stop, bus route and current location.
	 */
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		Log.d(LOG_TAG, "onCreateView");
		Log.d("Hello", "World");

		if (mapView == null) {
			mapView = new MapView(getActivity(), null);

			mapView.setTileSource(TileSourceFactory.MAPNIK);
			mapView.setClickable(true);
			mapView.setBuiltInZoomControls(true);

			// set default view for map (this seems to be important even when
			// it gets overwritten by plotBuses)
			mapController = mapView.getController();
			mapController.setZoom(mapView.getMaxZoomLevel() - 4);
			mapController.setCenter(NELSON_GRANVILLE);

			busLocnOverlay = createBusLocnOverlay();
			busStopLocationOverlay = createBusStopLocnOverlay();
			legendOverlay = createTextOverlay();

			// Order matters: overlays added later are displayed on top of
			// overlays added earlier.
			mapView.getOverlays().add(busStopLocationOverlay);
			mapView.getOverlays().add(busLocnOverlay);
			mapView.getOverlays().add(legendOverlay);
		}

		return mapView;
	}


	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		super.onCreateOptionsMenu(menu, inflater);
		inflater.inflate(R.menu.fragment_map_refresh, menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {

		if (item.getItemId() == R.id.map_refresh) {
			update(false);	
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	/**
	 * When view is destroyed, remove map view from its parent so that it can be
	 * added again when view is re-created.
	 */
	@Override
	public void onDestroyView() {
		Log.d(LOG_TAG, "onDestroyView");

		((ViewGroup) mapView.getParent()).removeView(mapView);

		super.onDestroyView();
	}

	@Override
	public void onDestroy() {
		Log.d(LOG_TAG, "onDestroy");

		super.onDestroy();
	}

	/**
	 * Update map when app resumes.
	 */
	@Override
	public void onResume() {
		super.onResume();
		Log.d(LOG_TAG, "onResume");

		update(true);
	}

	/**
	 * Set selected bus stop
	 * @param selectedStop  the selected stop
	 */
	public void setBusStop(BusStop selectedStop) {
		this.selectedStop = selectedStop;
	}

	/**
	 * Update bus location info for selected stop,
	 * update user location, zoomToFit status and repaint.
	 * 
	 * @Param zoomToFit  true if map must be zoomed to fit (when new bus stop has been selected)
	 */
	void update(boolean zoomToFit) {
		Log.d(LOG_TAG, "update - zoomToFit: " + zoomToFit);

		this.zoomToFit = zoomToFit;

		if(selectedStop != null) {
			selectedBus = null;
			new GetBusInfo().execute(selectedStop);
		}

		mapView.invalidate();
	}

	/**
	 * Create the overlay for bus markers.
	 */
	private ItemizedIconOverlay<OverlayItem> createBusLocnOverlay() {
		ResourceProxy rp = new DefaultResourceProxyImpl(getActivity());

		OnItemGestureListener<OverlayItem> gestureListener = new OnItemGestureListener<OverlayItem>() {
			/**
			 * Display bus route and description in dialog box when user taps
			 * bus.
			 * 
			 * @param index  index of item tapped
			 * @param oi the OverlayItem that was tapped
			 * @return true to indicate that tap event has been handled
			 */
			@Override
			public boolean onItemSingleTapUp(int index, OverlayItem oi) {
				index1 = index;
				selectedVehicleNo = selectedStop.getBusLocations().get(index).getVehicleNum();

				if (selectedBus == oi) {
					// if selected bus is tapped again

					index1 = -1;
					// return index to default
					selectedVehicleNo = null;
					// set selected vehicle number back to default

					selectedBus.setMarker(getResources().getDrawable(R.drawable.bus));

					mapView.getOverlays().clear();					
					mapView.getOverlays().add(busStopLocationOverlay);
					mapView.getOverlays().add(busLocnOverlay);
					mapView.getOverlays().add(legendOverlay);
					mapView.postInvalidate();

					selectedBus = null;
					return true;
				}
				else {
					if (selectedBus != null){
						// if another bus (NOT currently selected bus) is tapped
						selectedBus.setMarker(getResources().getDrawable(R.drawable.bus));
						// change previously selected bus back to the original icon
					}
					selectedBus = oi;
					selectedBus.setMarker(getResources().getDrawable(R.drawable.selected_bus));
					// change the selected bus's marker to the selected marker

					AlertDialog dlg = createSimpleDialog(oi.getTitle(), oi.getSnippet());
					dlg.show();

					BusLocation bus = null;
					for(BusLocation location : selectedStop.getBusLocations())
						// iterate through all bus locations
						if (location.getRoute().getName().equals(selectedBus.getTitle())){
							// find bus location that corresponds to the selected bus
							bus = location;
						}
					retrieveBusRoute(bus);

					return true;
				}
			}

			@Override
			public boolean onItemLongPress(int index, OverlayItem oi) {
				// do nothing
				return false;
			}
		};

		return new ItemizedIconOverlay<OverlayItem>(
				new ArrayList<OverlayItem>(), 
				getResources().getDrawable(R.drawable.map_pin_blue), 
				gestureListener, rp);
	}

	/**
	 * Create the overlay for bus stop marker.
	 */
	private ItemizedIconOverlay<OverlayItem> createBusStopLocnOverlay() {
		ResourceProxy rp = new DefaultResourceProxyImpl(getActivity());

		OnItemGestureListener<OverlayItem> gestureListener = new OnItemGestureListener<OverlayItem>() {
			/**
			 * Display bus stop description in dialog box when user taps
			 * stop.
			 * 
			 * @param index  index of item tapped
			 * @param oi the OverlayItem that was tapped
			 * @return true to indicate that tap event has been handled
			 */
			@Override
			public boolean onItemSingleTapUp(int index, OverlayItem oi) {
				AlertDialog dlg = createSimpleDialog(oi.getTitle(), oi.getSnippet());
				dlg.show();
				return true;
			}

			@Override
			public boolean onItemLongPress(int index, OverlayItem oi) {
				// do nothing
				return false;
			}
		};

		return new ItemizedIconOverlay<OverlayItem>(
				new ArrayList<OverlayItem>(), 
				getResources().getDrawable(R.drawable.stop), 
				gestureListener, rp);
	}

	/**
	 * Create the overlay for disclaimers displayed on top of map. 
	 * @return  text overlay used to display disclaimers
	 */
	private TextOverlay createTextOverlay() {
		ResourceProxy rp = new DefaultResourceProxyImpl(getActivity());
		Resources res = getResources();
		String legend = res.getString(R.string.legend);
		String osmCredit = res.getString(R.string.osm_credit);

		return new TextOverlay(rp, legend, osmCredit);
	}

	/**
	 * Create overlay for a single segment of a bus route.
	 * @returns a path overlay that can be used to plot a segment of a bus route
	 */
	private PathOverlay createRouteSegmentOverlay() {
		PathOverlay po = new PathOverlay(Color.RED, getActivity());
		Paint pathPaint = new Paint();
		pathPaint.setColor(Color.RED);
		pathPaint.setStrokeWidth(4.0f);
		pathPaint.setStyle(Style.STROKE);
		po.setPaint(pathPaint);
		return po;
	}


	/**
	 * Plot bus stop
	 */
	private void plotBusStop() {
		LatLon latlon = selectedStop.getLatLon();
		GeoPoint point = new GeoPoint(latlon.getLatitude(),
				latlon.getLongitude());
		OverlayItem overlayItem = new OverlayItem(Integer.valueOf(selectedStop.getStopNum()).toString(), 
				selectedStop.getLocationDesc(), point);
		busStopLocationOverlay.removeAllItems(); // make sure not adding
		// bus stop more than once
		busStopLocationOverlay.addItem(overlayItem);
	}

	/**
	 * Plot buses onto bus location overlay
	 * 
	 * @param zoomToFit  determines if map should be zoomed to bounds of plotted buses
	 */
	private void plotBuses(boolean zoomToFit) {
		double maxLat = selectedStop.getLatLon().getLatitude();
		double minLat = selectedStop.getLatLon().getLatitude();
		double maxLon = selectedStop.getLatLon().getLongitude();
		double minLon = selectedStop.getLatLon().getLongitude();

		LatLon stopLatLon = selectedStop.getLatLon();
		double stopLat = stopLatLon.getLatitude();
		double stopLon = stopLatLon.getLongitude();

		// clear existing buses from overlay
		busLocnOverlay.removeAllItems();

		// remove route overlays as there is now no bus selected
		OverlayManager om = mapView.getOverlayManager();
		om.removeAll(routeOverlays);

		List<BusLocation> busLocations = selectedStop.getBusLocations();

		if (busLocations.size() > 0) {
			for (BusLocation next : busLocations) {

				plotBus(busLocnOverlay, next);
				if ((next.getLatLon().getLatitude() != 0) && (next.getLatLon().getLongitude() != 0)) {
					// if next's lat or lon equals zero, skip update for minLat, maxLat, minLon and maxLon

					if (next.getLatLon().getLatitude() > maxLat)
						maxLat = next.getLatLon().getLatitude();
					if (next.getLatLon().getLatitude() < minLat)
						minLat = next.getLatLon().getLatitude();
					if (next.getLatLon().getLongitude() > maxLon)
						maxLon = next.getLatLon().getLongitude();
					if (next.getLatLon().getLongitude() < minLon)
						minLon = next.getLatLon().getLongitude();		
				}

			}
			if (zoomToFit){
				double spanLat = (maxLat - minLat)/2;
				double spanLon = (maxLon - minLon)/2;

				mapController.setCenter(new GeoPoint(minLat + (spanLat), minLon + (spanLon)));
				mapController.zoomToSpan((int)(((maxLat - minLat)*1000000)  + BORDER), (int)(((maxLon - minLon)*1000000) + BORDER));
			}

		}
		else {
			// no buses to plot so centre map on bus stop
			mapController.setCenter(new GeoPoint(stopLat, stopLon));

		}	

		if (index1 >= 0){
			// the following code runs if the refresh button is pressed while there is currently a selected bus
			// it ensures that the selected bus remains selected and its route is still displayed
			selectedBus = busLocnOverlay.getItem(index1);
			// returns the selected bus value to selectedBus after it was assigned a null value
			BusLocation newSelectedBus = selectedStop.getBusLocations().get(index1);

			if (newSelectedBus.getVehicleNum().equals(selectedVehicleNo)){
				// ensures that index refers to the same selected bus with the same vehicle number
				// if selected bus is out of service after refresh, then no selected bus and no route are displayed
				busLocnOverlay.getItem(index1).setMarker(getResources().getDrawable(R.drawable.selected_bus));
				
				BusLocation bus = null;
				for(BusLocation location : selectedStop.getBusLocations()){
					if (location.getRoute().getName().equals(selectedBus.getTitle())){
						bus = location;
					}
				}
				retrieveBusRoute(bus);
			}
			else{
				// if selected bus is no longer in service, return default values to the following variables
				selectedBus = null;
				index1 = -1;
				selectedVehicleNo = null;
			}
		}
	}


	/**
	 * Plot a bus on the specified overlay.
	 */
	private void plotBus(ItemizedIconOverlay<OverlayItem> overlay,
			BusLocation bl) {
		LatLon latlon = bl.getLatLon();
		GeoPoint point = new GeoPoint(latlon.getLatitude(), latlon.getLongitude());
		OverlayItem overlayItem = new OverlayItem(bl.getRoute().toString(),
				bl.getDescription(), point);

		overlayItem.setMarker(getResources().getDrawable(R.drawable.bus));
		overlayItem.setMarkerHotspot(HotspotPlace.CENTER);
		overlay.addItem(overlayItem);
	}


	/**
	 * Get bus route for a particular bus and plot it.
	 * Use cached route, if available.
	 * @param bus  the bus location object associated with the route to be retrieved
	 */
	private void retrieveBusRoute(BusLocation bus) {
		if (bus.getRoute().hasSegments())
			plotBusRoute(bus.getRoute());
		else
			new GetRouteInfo().execute(bus.getRoute());
	}

	/**
	 * Plot bus route onto route overlays.  If rte is null,
	 * no route is plotted (any existing route is cleared).
	 * 
	 * @param rte  the bus route
	 */
	private void plotBusRoute(BusRoute rte) {
		routeOverlays.clear();
		if(rte != null){
			List<Segment> segments = rte.getSegments();
			for (Segment s: segments){
				PathOverlay po = createRouteSegmentOverlay();
				List<LatLon> latlons = s.getPoints();
				for (LatLon l: latlons){
					GeoPoint point = new GeoPoint(l.getLatitude(), l.getLongitude());
					po.addPoint(point);}
				routeOverlays.add(po);
			}
		}
		mapView.getOverlays().clear();
		mapView.getOverlays().addAll (routeOverlays);
		mapView.getOverlays().add(busStopLocationOverlay);
		mapView.getOverlays().add(busLocnOverlay);
		mapView.getOverlays().add(legendOverlay);
		mapView.postInvalidate();
	}

	/**
	 * Helper to create simple alert dialog to display message
	 * @param title  the title to be displayed at top of dialog
	 * @param msg  message to display in dialog
	 * @return  the alert dialog
	 */
	private AlertDialog createSimpleDialog(String title, String msg) {
		AlertDialog.Builder dialogBldr = new AlertDialog.Builder(getActivity());
		dialogBldr.setTitle(title);
		dialogBldr.setMessage(msg);
		dialogBldr.setNeutralButton(R.string.ok, null);

		return dialogBldr.create();
	}

	/** 
	 * Asynchronous task to get bus location estimates from Translink service.
	 * Displays progress dialog while running in background.  
	 */
	private class GetBusInfo extends
	AsyncTask<BusStop, Void, Void> {
		private ProgressDialog dialog = new ProgressDialog(getActivity());
		private boolean success = true;
		private String errorMsg;

		@Override
		protected void onPreExecute() {
			dialog.setMessage("Retrieving bus info...");
			dialog.show();
		}

		@Override
		protected Void doInBackground(BusStop... selectedStops) {
			BusStop selectedStop = selectedStops[0];

			try {
				tlService.addBusLocationsForStop(selectedStop);
			} catch (TranslinkException e) {
				e.printStackTrace();
				errorMsg = e.getMessage();
				errorMsg += "\n(code: " + e.getCode() + ")";
				success = false;
			}

			return null;
		}

		@Override
		protected void onPostExecute(Void dummy) {
			dialog.dismiss();

			if (success) {
				plotBuses(zoomToFit);
				plotBusStop();
				mapView.invalidate();
			} else {
				String msg = "Unable to get information for stop #: " + selectedStop;
				msg += "\n\n" + errorMsg;

				AlertDialog dialog = createSimpleDialog("Error", msg);
				dialog.show();
			}
		}
	}

	/** 
	 * Asynchronous task to get bus route from Translink service.
	 * Adds bus route to cache so we don't have to go get it again if user
	 * decides to display this route again. 
	 * Displays progress dialog while running in background.  
	 */
	private class GetRouteInfo extends AsyncTask<BusRoute, Void, Void> {
		private BusRoute route;
		private boolean success = true;

		@Override
		protected void onPreExecute() {
		}

		@Override
		protected Void doInBackground(BusRoute... routes) {
			route = routes[0];

			try {
				tlService.parseKMZ(route);
			} catch (TranslinkException e) {
				e.printStackTrace();
				success = false;
			}

			return null;
		}

		@Override
		protected void onPostExecute(Void dummy) {
			if (success) {
				plotBusRoute(route);
			} else {
				AlertDialog dialog = createSimpleDialog("Error", "Unable to retrieve route...");
				dialog.show();
			}
		}
	}
}
