package edu.ucla.cs.eventmap;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import com.firebase.geofire.GeoFire;
import com.firebase.geofire.GeoLocation;
import com.firebase.geofire.GeoQuery;
import com.firebase.geofire.GeoQueryEventListener;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.mapbox.android.core.location.LocationEngine;
import com.mapbox.android.core.location.LocationEnginePriority;
import com.mapbox.android.core.location.LocationEngineProvider;
import com.mapbox.mapboxsdk.Mapbox;
import com.mapbox.mapboxsdk.annotations.Marker;
import com.mapbox.mapboxsdk.annotations.MarkerOptions;
import com.mapbox.mapboxsdk.annotations.Polyline;
import com.mapbox.mapboxsdk.annotations.PolylineOptions;
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.geometry.LatLngBounds;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback;
import com.mapbox.mapboxsdk.plugins.locationlayer.LocationLayerPlugin;
import com.mapbox.mapboxsdk.plugins.locationlayer.modes.CameraMode;
import com.mapbox.mapboxsdk.plugins.locationlayer.modes.RenderMode;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

import static com.mapbox.mapboxsdk.maps.MapView.DID_FINISH_RENDERING_MAP_FULLY_RENDERED;

public class MapsActivity extends AppCompatActivity {
    final private int REQUEST_CODE_ASK_PERMISSIONS = 123;
    private static final String TAG = "MapsActivity";
    public FirebaseDatabase database;
    private DatabaseReference userLocations;
    private DatabaseReference events;
    private DatabaseReference eventsGeo;
    private DatabaseReference attendance;
    private DatabaseReference comments;
    private GeoFire geoFireAttendance;
    private GeoQueryEventListener geoAttendanceListener;
    private GeoFire geoFire;
    private GeoQueryEventListener geoListener;
    private LocationLayerPlugin locationPlugin;
    private LocationEngine locationEngine;
    private MapboxMap mapboxMap;
    private MapView mapView;
    private MapboxMap.OnMarkerClickListener markerClickListener;
    private double cameraRadius;
    private double lastZoom;
    private Polyline polyline;
    private Marker marker;
    private int querySize = 0;
    private int newQuerySize;
    private int attendQuerySize = 0;
    private int newAttendQuerySize;
    public static String uid;
    public String username;
    private FloatingActionButton fab;
    private boolean eventHasClicked = false;
    private double eRadius = 0.025;
    private Handler locationReporter;
    private GeoQuery eventQuery;
    private GeoQuery eventAttendQuery;
    private HashMap<Integer, Marker> loadedMarkers;
    private HashMap<Integer, Event> loadedEvents;
    private HashMap<Integer, Integer> eventPings;
    private ArrayList<Comment> commentList;
    private ArrayList<Event> candidateEvents;
    private int attendingEventHash = 0;
    private Event attendingEvent;
    private boolean performingOperation = false; //Used to essentially hold a lock in order to prevent conflicts when events either get deleted or we're calculating attendance

    private double dist(double lat1, double lng1, double lat2, double lng2) { //Computes distance between two points in terms of latitude and longitude, returns in km
        return Math.acos(Math.cos(Math.toRadians(90 - lat1)) * Math.cos(Math.toRadians(90 - lat2)) + Math.sin(Math.toRadians(90 - lat1)) * Math.sin(Math.toRadians(90 - lat2)) * Math.cos(Math.toRadians(lng1 - lng2))) * 6371;
    }

    private void newCameraRadius() { //Determines what the effective search radius should be given the current coordinate bounds given on screen
        LatLngBounds cameraBounds = mapboxMap.getProjection().getVisibleRegion().latLngBounds;
        //Bit of an overestimate since the height of the phone will be less than the width, but using width would be an underestimate - no big deal since we verify later that the point is actually in the visible region
        cameraRadius = dist(cameraBounds.getLatNorth(), cameraBounds.getCenter().getLongitude(), cameraBounds.getLatSouth(), cameraBounds.getCenter().getLongitude());
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //Various database initializations
        uid = getIntent().getStringExtra("uid");
        username = getIntent().getStringExtra("username");
        database = FirebaseDatabase.getInstance();
        userLocations = database.getReference("/userLocations/");
        events = database.getReference("/events/");
        eventsGeo = database.getReference("/geoevents/");
        attendance = database.getReference("/attendance/");
        comments = database.getReference("/comments");
        geoFire = new GeoFire(eventsGeo);
        geoFireAttendance = new GeoFire(eventsGeo);
        loadedEvents = new HashMap<>();
        loadedMarkers = new HashMap<>();
        eventPings = new HashMap<>();
        Log.v(TAG, "Firebase UID: " + uid);
        //Get the Mapbox API instance, use the access token key
        Mapbox.getInstance(this, getString(R.string.access_token));

        //Sets our content view to the Mapbox view
        setContentView(R.layout.activity_maps);
        mapView = findViewById(R.id.mapView);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(MapboxMap mapboxMap) { //Once the map finishes initializing
                MapsActivity.this.mapboxMap = mapboxMap;
                lastZoom = mapboxMap.getCameraPosition().zoom; //Take note of the last zoom level - we'll use it in a hacky workaround to trigger some events
                enableLocationPlugin();
                setupOnMarkerClick();
                setupClickHandlers();
                setupTasks();
            }
        });
    }

    @SuppressWarnings({"MissingPermission"})
    private void enableLocationPlugin() { //Checks for location permissions and requests them, if we already have them then initialize location tracking + camera movement based on location
        Log.v(TAG, "In enablelocationplugin");
        // Check if permissions are enabled and if not request
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat
                    .requestPermissions(MapsActivity.this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_CODE_ASK_PERMISSIONS);
        } else {
            initializeLocationEngine();
            locationPlugin = new LocationLayerPlugin(mapView, mapboxMap, locationEngine);
            locationPlugin.setRenderMode(RenderMode.COMPASS);
            locationPlugin.setCameraMode(CameraMode.TRACKING_COMPASS);
        }
    }

    @SuppressWarnings({"MissingPermission"})
    private void initializeLocationEngine() { //For the sake of the UI, show location updates in real time - note however that this does not send the user's location to the server, merely on the client-side
        locationEngine = new LocationEngineProvider(this).obtainBestLocationEngineAvailable();
        locationEngine.setPriority(LocationEnginePriority.HIGH_ACCURACY);
        locationEngine.setFastestInterval(1000);
        locationEngine.setInterval(0);
        locationEngine.activate();
        locationEngine.requestLocationUpdates();
    }

    private void setupTasks() {
        locationReporter = new Handler();
        Runnable locationReporterTask = new Runnable() { //Used to determine event attendance, finds which events are near us and sends pings
            @Override
            public void run() {
                eventAttendQuery = null; //Stops any querying that was in progress
                geoAttendanceListener = null; //Stops any querying that was in progress
                Location last = locationEngine.getLastLocation(); //Don't want it to possibly change while we're looking things up
                candidateEvents = new ArrayList<>();
                Log.v(TAG, "Checking what events are near us");
                Handler attendWait = new Handler();
                Runnable attend = new Runnable() {
                    public void run() {
                        if (!performingOperation) { //Can only process attendance if a deletion isn't occurring
                            performingOperation = true;
                            if ((newAttendQuerySize = candidateEvents.size()) > attendQuerySize) { //Since the Firebase loads occur asynchronously, use this to essentially check if we're still loading events in
                                attendQuerySize = newAttendQuerySize;
                                attendWait.postDelayed(this, 100);
                            } else {
                                long current = System.currentTimeMillis();
                                if (attendingEventHash != 0 && attendingEvent != null) { //If we're currently attending an event
                                    double distance = dist(last.getLatitude(), last.getLongitude(), attendingEvent.lat, attendingEvent.lng); //Determine distance between most recent location and event
                                    if (distance > attendingEvent.radius || distance / attendingEvent.radius > 0.8 || (current > (attendingEvent.time + attendingEvent.duration))) {
                                        //No longer in attendance or might be time to consider another event based on being on the outskirts of the event or event ended, so remove us from the old attendance data
                                        attendance.child(String.valueOf(attendingEventHash)).child(uid).removeValue(); //Remove the pings that we had been keeping track of
                                        attendingEventHash = 0;
                                    }
                                }
                                if (attendingEventHash == 0) { //Either we haven't attended any event or we just stopped attending one
                                    HashSet<Integer> keys = new HashSet<>(eventPings.keySet());
                                    for (int hash : keys) {
                                        if (!candidateEvents.contains(new Event(hash))) { //Remove and stop tracking pings to an event that's no longer in our radius
                                            eventPings.remove(hash);
                                        }
                                    }
                                    //Find if we're currently in the radius of any events
                                    ArrayList<Event> inRange = new ArrayList<>();
                                    ArrayList<Double> ratios = new ArrayList<>();
                                    double distance;
                                    for (Event e : candidateEvents) {
                                        if ((distance = dist(last.getLatitude(), last.getLongitude(), e.lat, e.lng)) <= e.radius && eventPings.get(e.hash) >= 2) { //If we're in range and have been in the event radius for 2 pings or more
                                            inRange.add(e); //Consider it in range
                                            ratios.add(e.radius / distance); //Add the ratio of radius to distance, we'll be using it to determine which we're closest to/most likely to be attending
                                        }
                                    }
                                    double highest = 0; //To store the highest radius/distance ratio
                                    Event highEvent = new Event(); //Corresponding event with said highest radius/distance ratio
                                    for (int i = 0; i < inRange.size(); i++) {
                                        if (ratios.get(i) > highest) { //If the ratio of radius to distance is higher, that's the event we're most likely attending
                                            highest = ratios.get(i);
                                            highEvent = inRange.get(i);
                                        } else if (ratios.get(i) == highest) { //If the ratios are equal, we then consider which event we've had more pings at - choose the one we've been at longer
                                            Event iEvent = inRange.get(i);
                                            int highping = eventPings.get(highEvent.hash);
                                            int iping = eventPings.get(iEvent.hash);
                                            if ((iping / ratios.get(i)) > (highping / highest)) {
                                                highest = ratios.get(i);
                                                highEvent = inRange.get(i);
                                            }
                                            iEvent = null;
                                        }
                                    }
                                    if (highEvent.hash != 0) { //Make sure that a valid event has been placed into here, because the loop may not have even run
                                        attendingEvent = highEvent; //Set that we're in attendance
                                        attendingEventHash = highEvent.hash;
                                        attendance.child(String.valueOf(attendingEventHash)).child(uid).setValue(new Pings(eventPings.get(attendingEventHash))); //List the user as being in attendance
                                    }
                                }
                            }
                            performingOperation = false;
                        }
                    }
                };
                eventAttendQuery = geoFireAttendance.queryAtLocation(new GeoLocation(last.getLatitude(), last.getLongitude()), 0.5); //250m is our max attendance radius anyway, but just do 0.5km to minimize error from GeoFire
                geoAttendanceListener = new GeoQueryEventListener() {
                    @Override
                    public void onKeyEntered(String key, GeoLocation location) {
                        events.child(key).addListenerForSingleValueEvent(new ValueEventListener() { //When new events enter our location radius
                            @Override
                            public void onDataChange(DataSnapshot dataSnapshot) {
                                    long current = System.currentTimeMillis();
                                    Event e = dataSnapshot.getValue(Event.class);
                                    if ((current > e.time) && current < (e.time + e.duration)) { //Only consider it a candidate if the event is currently going on
                                        candidateEvents.add(e);
                                        if (eventPings.containsKey(e.hash)) { //Has pinged before, increase the number of pings if we're in the event radius
                                            if (dist(last.getLatitude(), last.getLongitude(), e.lat, e.lng) <= e.radius) {
                                                eventPings.put(e.hash, eventPings.get(e.hash) + 1);
                                            }
                                        } else { //Hasn't pinged before, initialize it by saying we've done 1 ping if we're in the event radius
                                            if (dist(last.getLatitude(), last.getLongitude(), e.lat, e.lng) <= e.radius) {
                                                eventPings.put(e.hash, 1);
                                            }
                                        }
                                    }
                            }

                            @Override
                            public void onCancelled(DatabaseError databaseError) {

                            }
                        });
                    }

                    @Override
                    public void onKeyExited(String key) { //When events get deleted
                        Handler deleteWait = new Handler();
                        Runnable delete = new Runnable() {
                            public void run() {
                                if (!performingOperation) {
                                    performingOperation = true; //Prevent the attendance function from determining attendance until we delete this event
                                    if (eventPings.containsKey(Integer.valueOf(key))) { //Should always return true, but never hurts to check
                                        if (Integer.valueOf(key) == attendingEventHash) { //No need to iterate over to find the event we need to remove if it's the one we're attending
                                            eventPings.remove(attendingEventHash);
                                            candidateEvents.remove(attendingEvent);
                                            attendingEvent = null;
                                            attendingEventHash = 0;
                                        }
                                        else { //Iterate over the list to find the one that we need to remove
                                            Event toRemove = new Event(Integer.valueOf(key));
                                            for (Event e : candidateEvents) {
                                                if (e.equals(toRemove)) {
                                                    toRemove = new Event(e.owner, e.username, e.name, e.time, e.duration, e.radius, e.lat, e.lng, e.hash);
                                                    break;
                                                }
                                            }
                                            candidateEvents.remove(toRemove);
                                            eventPings.remove(Integer.valueOf(key));
                                        }
                                        attendQuerySize--; //Otherwise the attendance won't be re-run properly
                                    }
                                    performingOperation = false;
                                    attendWait.post(attend); //Explicitly re-call attendance once we perform the deletion
                                }
                                else {
                                    deleteWait.postDelayed(this, 100); //If we're performing an operation, try again later
                                }
                            }
                        };
                        deleteWait.post(delete); //Try to run it right away
                    }

                    @Override
                    public void onKeyMoved(String key, GeoLocation location) {
                    }

                    @Override
                    public void onGeoQueryReady() {
                        attendWait.postDelayed(attend, 100);
                    }

                    @Override
                    public void onGeoQueryError(DatabaseError error) {
                    }
                };
                eventAttendQuery.addGeoQueryEventListener(geoAttendanceListener);
                locationReporter.postDelayed(this, 60 * 1000 * 10); //Run every 10 minutes
            }
        };
        mapView.addOnMapChangedListener(new MapView.OnMapChangedListener() { //Add the appropriate listeners + only determine events near us when the map has been rendered
            @Override
            public void onMapChanged(int change) {
                if (change == DID_FINISH_RENDERING_MAP_FULLY_RENDERED) { //Only load events once we're fully rendered, only start processing our camera changes once we're fully rendered
                    Log.v(TAG, "Map fully rendered");
                    locationReporterTask.run();
                    newCameraRadius();
                    mapboxMap.setOnCameraIdleListener(new MapboxMap.OnCameraIdleListener() {
                        @Override
                        public void onCameraIdle() { //When we move the camera, load events to find what should currently be in view
                            Log.v(TAG, "In onCameraIdle");
                            double newZoom = mapboxMap.getCameraPosition().zoom;
                            if (newZoom != lastZoom) { //If the user changed the zoom level, we need to adjust the camera's effective radius
                                Log.v(TAG, "Old radius: " + String.valueOf(cameraRadius) + " km");
                                Log.v(TAG, "Updating radii");
                                newCameraRadius();
                                Log.v(TAG, "New radius: " + String.valueOf(cameraRadius) + " km");
                            }
                            lastZoom = newZoom;
                            Log.v(TAG, "Loading events from server after camera change");
                            if (geoListener != null) {
                                geoListener = null;
                                eventQuery = null;
                            }
                            loadEventsInCamera();
                        }
                    });
                    mapboxMap.moveCamera(CameraUpdateFactory.zoomIn());
                    mapboxMap.setOnMarkerClickListener(markerClickListener);
                }
            }
        });
    }

    private void loadEventsInCamera() { //Used when the camera finishes moving/zoom levels change
        eventQuery = geoFire.queryAtLocation(new GeoLocation(mapboxMap.getProjection().getVisibleRegion().latLngBounds.getCenter().getLatitude(), mapboxMap.getProjection().getVisibleRegion().latLngBounds.getCenter().getLongitude()), cameraRadius); //Query around the current camera position and radius
        ArrayList<Event> queryEvents = new ArrayList<>();
        geoListener = new GeoQueryEventListener() {
            @Override
            public void onKeyEntered(String key, GeoLocation location) {
                if (mapboxMap.getProjection().getVisibleRegion().latLngBounds.contains(new LatLng(location.latitude, location.longitude))) { //Only process the data if the event is actually visible - overestimate correction
                    events.child(key).addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot dataSnapshot) {
                            Event e = dataSnapshot.getValue(Event.class);
                            if (!loadedEvents.containsKey(e.hash)) { //Newly added, therefore not present
                                long current = System.currentTimeMillis();
                                if (e.owner.equals(uid) || ((current > e.time) && current < (e.time + e.duration))) { //Only show the marker if the event is currently going on or we're the event owner
                                    loadedEvents.put(e.hash, e);
                                    Marker markToAdd = mapboxMap.addMarker(new MarkerOptions().position(new LatLng(e.lat, e.lng)));
                                    markToAdd.setTitle(String.valueOf(e.hash));
                                    loadedMarkers.put(e.hash, markToAdd);
                                }
                            }
                            queryEvents.add(e);
                        }

                        @Override
                        public void onCancelled(DatabaseError databaseError) {

                        }
                    });
                }
            }

            @Override
            public void onKeyExited(String key) {
                if (loadedEvents.containsKey(Integer.valueOf(key))) {
                    Event e = loadedEvents.get(Integer.valueOf(key));
                    if (mapboxMap.getProjection().getVisibleRegion().latLngBounds.contains(new LatLng(e.lat, e.lng))) { //Double check and make sure the event being deleted is in our bounds
                        mapboxMap.removeMarker(loadedMarkers.get(Integer.valueOf(key)));
                        loadedEvents.remove(Integer.valueOf(key));
                        loadedMarkers.remove(Integer.valueOf(key));
                    }
                }
            }

            @Override
            public void onKeyMoved(String key, GeoLocation location) {

            }

            @Override
            public void onGeoQueryReady() { //Hacky workaround where we then see what events we need to remove - since they're not visible anyway, no rush
                Handler removeWait = new Handler();
                Runnable remover = new Runnable() {
                    public void run() {
                        if ((newQuerySize = queryEvents.size()) > querySize) {
                            querySize = newQuerySize;
                            removeWait.postDelayed(this, 100);
                        } else {
                            Log.v(TAG, "Finished loading new events, removing any that may no longer be visible");
                            HashSet<Integer> keys = new HashSet<>(loadedEvents.keySet());
                            for (int hash : keys) {
                                if (!queryEvents.contains(loadedEvents.get(hash))) { //We should delete the event/marker, it's no longer present
                                    mapboxMap.removeMarker(loadedMarkers.get(hash));
                                    loadedEvents.remove(hash);
                                    loadedMarkers.remove(hash);
                                }
                            }
                            querySize = 0;
                        }
                    }
                };
                removeWait.postDelayed(remover, 100);
            }

            @Override
            public void onGeoQueryError(DatabaseError error) {

            }
        };
        eventQuery.addGeoQueryEventListener(geoListener);
    }

    private void setupClickHandlers() {
        fab = findViewById(R.id.eventFAB);
        FloatingActionButton cancelFab = findViewById(R.id.cancelFAB);
        SeekBar radiusBar = findViewById(R.id.radiusBar);
        TextView radiusText = findViewById(R.id.radiusText);
        MapboxMap.OnMapClickListener dragger = new MapboxMap.OnMapClickListener() {
            @Override
            public void onMapClick(@NonNull LatLng point) {
                if (polyline != null) {
                    mapboxMap.removePolyline(polyline);
                }
                radiusBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {

                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        eRadius = ((double) seekBar.getProgress() / 1000);
                        radiusText.setText("Radius: " + Double.toString(eRadius * 1000) + " m");
                        if (polyline != null) {
                            mapboxMap.removePolyline(polyline);
                        }
                        polyline = mapboxMap.addPolyline(drawCircle(point, Color.BLUE, eRadius * 1000));
                    }
                });
                ValueAnimator markerAnimator = ObjectAnimator.ofObject(marker, "position", new LatLngEvaluator(), marker.getPosition(), point);
                markerAnimator.setDuration(2000);
                markerAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        if (polyline != null) {
                            mapboxMap.removePolyline(polyline);
                        }
                        polyline = mapboxMap.addPolyline(drawCircle(point, Color.BLUE, eRadius * 1000));
                    }
                });
                markerAnimator.start();
            }
        };
        View.OnClickListener canceller = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                eventHasClicked = false;
                if (polyline != null) {
                    mapboxMap.removePolyline(polyline);
                }
                fab.setImageDrawable(getResources().getDrawable(R.drawable.plusgrey));
                mapboxMap.removeMarker(marker);
                mapboxMap.removeOnMapClickListener(dragger);
                radiusBar.setVisibility(View.INVISIBLE);
                radiusBar.setOnSeekBarChangeListener(null);
                radiusText.setVisibility(View.INVISIBLE);
                cancelFab.setVisibility(View.INVISIBLE);
                cancelFab.setOnClickListener(null);
                mapboxMap.setOnMarkerClickListener(markerClickListener);
            }
        };
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!eventHasClicked) {
                    mapboxMap.setOnMarkerClickListener(null);
                    eventHasClicked = true;
                    LatLng last = new LatLng(locationEngine.getLastLocation());
                    fab.setImageDrawable(getResources().getDrawable(R.drawable.checkgrey));
                    marker = mapboxMap.addMarker(new MarkerOptions().position(last));
                    polyline = mapboxMap.addPolyline(drawCircle(last, Color.BLUE, eRadius * 1000));
                    mapboxMap.addOnMapClickListener(dragger);
                    radiusBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar seekBar) {

                        }

                        @Override
                        public void onStopTrackingTouch(SeekBar seekBar) {
                            eRadius = ((double) seekBar.getProgress() / 1000);
                            radiusText.setText("Radius: " + Double.toString(eRadius * 1000) + " m");
                            if (polyline != null) {
                                mapboxMap.removePolyline(polyline);
                            }
                            polyline = mapboxMap.addPolyline(drawCircle(last, Color.BLUE, eRadius * 1000));
                        }
                    });
                    radiusText.setVisibility(View.VISIBLE);
                    radiusText.setText("Radius: " + Integer.toString(radiusBar.getProgress()) + ".0 m");
                    radiusBar.setVisibility(View.VISIBLE);
                    cancelFab.setOnClickListener(canceller);
                    cancelFab.setVisibility(View.VISIBLE);
                } else {
                    double distFromEvent = dist(locationEngine.getLastLocation().getLatitude(), locationEngine.getLastLocation().getLongitude(), marker.getPosition().getLatitude(), marker.getPosition().getLongitude());
                    Log.v(TAG, "Distance from event: " + Double.toString(distFromEvent));
                    if (distFromEvent <= eRadius) {
                        View eventDialogLayout = getLayoutInflater().inflate(R.layout.adddialog, null);
                        AlertDialog.Builder eventDialogBuilder = new AlertDialog.Builder(MapsActivity.this);
                        eventDialogBuilder.setView(eventDialogLayout);
                        eventDialogBuilder.setTitle("Further Event Info");
                        eventDialogBuilder.setPositiveButton("Add Event", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                EditText durationText = eventDialogLayout.findViewById(R.id.eventDurationText);
                                if (durationText.getText().toString().trim().length() == 0) {
                                    Toast.makeText(MapsActivity.this, "Must specify an event duration!", Toast.LENGTH_SHORT)
                                            .show();
                                }
                                else {
                                    EditText nameText = eventDialogLayout.findViewById(R.id.eventNameText);
                                    DatePicker startDate = eventDialogLayout.findViewById(R.id.eventDatePicker);
                                    TimePicker startTime = eventDialogLayout.findViewById(R.id.eventTimePicker);
                                    long eventTime = new GregorianCalendar(startDate.getYear(), startDate.getMonth(), startDate.getDayOfMonth(), startTime.getCurrentHour(), startTime.getCurrentMinute()).getTimeInMillis();
                                    String[] durationString = durationText.getText().toString().split(":");
                                    long duration = TimeUnit.HOURS.toMillis(Long.valueOf(durationString[0]));
                                    if (durationString.length >= 2) {
                                        duration = duration + TimeUnit.MINUTES.toMillis(Long.valueOf(durationString[1]));
                                    }
                                    eventHasClicked = false;
                                    if (polyline != null) {
                                        mapboxMap.removePolyline(polyline);
                                    }
                                    String eventName = nameText.getText().toString();
                                    int nameHash = (uid + eventName + String.valueOf(System.currentTimeMillis())).hashCode(); //Hash it w/ the user's UID and the current Unix time
                                    mapboxMap.removeMarker(marker);
                                    Marker markToAdd = mapboxMap.addMarker(new MarkerOptions().position(marker.getPosition()));
                                    markToAdd.setTitle(String.valueOf(nameHash));
                                    marker = null;
                                    loadedEvents.put(nameHash, new Event(uid, username, eventName, eventTime, duration, eRadius, markToAdd.getPosition().getLatitude(), markToAdd.getPosition().getLongitude(), nameHash));
                                    loadedMarkers.put(nameHash, markToAdd);
                                    geoFire.setLocation(Integer.toString(nameHash), new GeoLocation(markToAdd.getPosition().getLatitude(), markToAdd.getPosition().getLongitude()), new GeoFire.CompletionListener() {
                                        @Override
                                        public void onComplete(String key, DatabaseError error) {

                                        }
                                    });
                                    events.child(Integer.toString(nameHash)).setValue(loadedEvents.get(nameHash));
                                    fab.setImageDrawable(getResources().getDrawable(R.drawable.plusgrey));
                                    mapboxMap.removeOnMapClickListener(dragger);
                                    radiusBar.setVisibility(View.INVISIBLE);
                                    radiusBar.setOnSeekBarChangeListener(null);
                                    radiusText.setVisibility(View.INVISIBLE);
                                    cancelFab.setOnClickListener(null);
                                    cancelFab.setVisibility(View.INVISIBLE);
                                    mapboxMap.setOnMarkerClickListener(markerClickListener);
                                }
                            }
                        });
                        AlertDialog eventDialog = eventDialogBuilder.create();
                        eventDialog.show();
                    }
                    else {
                        Toast.makeText(MapsActivity.this, "Must be in event radius to create event!", Toast.LENGTH_SHORT)
                                .show();
                    }
                }
            }
        });
    }

    private void setupOnMarkerClick() {
        markerClickListener = new MapboxMap.OnMarkerClickListener() {
            @Override
            public boolean onMarkerClick(@NonNull Marker marker) {
                View alertLayout = getLayoutInflater().inflate(R.layout.dialog, null);
                Event loadedEvent = loadedEvents.get(Integer.valueOf(marker.getTitle()));
                ListView commentView = alertLayout.findViewById(R.id.commentList);
                EditText commentEdit = alertLayout.findViewById(R.id.commentEdit);
                Button commentButton = alertLayout.findViewById(R.id.commentButton);
                commentList = new ArrayList<>();
                CommentListAdapter adapter = new CommentListAdapter(MapsActivity.this, R.layout.row_comment, commentList, loadedEvent.owner.equals(uid));
                commentView.setAdapter(adapter);
                AlertDialog.Builder infoDialogBuilder = new AlertDialog.Builder(MapsActivity.this);
                infoDialogBuilder.setView(alertLayout);
                infoDialogBuilder.setTitle("Event Information");
                infoDialogBuilder.setMessage("Event Name: " + loadedEvent.name);
                AlertDialog infoDialog = infoDialogBuilder.create();
                if (loadedEvent.owner.equals(uid)) { //Enable event deletion since we're the owner
                    Button eDeleteButton = alertLayout.findViewById(R.id.deleteEButton);
                    eDeleteButton.setVisibility(View.VISIBLE);
                    eDeleteButton.setEnabled(true);
                    eDeleteButton.setOnClickListener(new View.OnClickListener() { //Remove all the data associated with the event when the owner deletes it
                        @Override
                        public void onClick(View v) {
                            infoDialog.cancel();
                            events.child(marker.getTitle()).removeValue();
                            comments.child(marker.getTitle()).removeValue();
                            attendance.child(marker.getTitle()).removeValue();
                            eventsGeo.child(marker.getTitle()).removeValue();
                        }
                    });
                }
                commentButton.setOnClickListener(new View.OnClickListener() { //Add the comment, adding it to the database will trigger the local listener
                    @Override
                    public void onClick(View v) {
                        long hash = System.currentTimeMillis();
                        comments.child(marker.getTitle()).child(String.valueOf(hash)).setValue(new Comment(marker.getTitle(), uid, username, commentEdit.getText().toString(), new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()), hash, 0));
                    }
                });
                ChildEventListener commentListener = new ChildEventListener() { //Comment is being added
                    @Override
                    public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                        Log.v(TAG, "In onchildadded");
                        Comment c = dataSnapshot.getValue(Comment.class);
                        if (c.pin == 1) { //pinned, so add to top of list
                            commentList.add(0, c);
                        }
                        else {
                            commentList.add(c);
                        }
                        adapter.notifyDataSetChanged();
                    }

                    @Override
                    public void onChildChanged(DataSnapshot dataSnapshot, String s) { //used for when a new comment comes in and is pinned/unpinned, only time this is triggered
                        Log.v(TAG, "In onchildchanged");
                        Comment c = dataSnapshot.getValue(Comment.class);
                        if (c.pin == 1) { //Pinned, so move it to the top
                            commentList.remove(c);
                            commentList.add(0, c);
                        }
                        else {
                            int index = commentList.indexOf(c);
                            commentList.remove(c);
                            commentList.add(index, c);
                        }
                        adapter.notifyDataSetChanged();
                    }

                    @Override
                    public void onChildRemoved(DataSnapshot dataSnapshot) { //Someone's deleted a comment
                        Log.v(TAG, "In onchildremoved");
                        commentList.remove(dataSnapshot.getValue(Comment.class));
                        adapter.notifyDataSetChanged();
                    }

                    @Override
                    public void onChildMoved(DataSnapshot dataSnapshot, String s) {
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                    }
                };
                ValueEventListener attendanceListener = new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        long current = System.currentTimeMillis();
                        String eventStatus = "";
                        if (current > loadedEvent.time && current < loadedEvent.time + loadedEvent.duration) { //Event is in progress
                            long hours = TimeUnit.MILLISECONDS.toHours(loadedEvent.time + loadedEvent.duration - current);
                            long minutes = TimeUnit.MILLISECONDS.toMinutes(loadedEvent.time + loadedEvent.duration - current) - TimeUnit.HOURS.toMinutes(hours);
                            eventStatus = "In Progress - " + String.valueOf(hours) + " hours, " + String.valueOf(minutes) + " minutes left";
                        }
                        else if (current < loadedEvent.time) {//Hasn't started yet
                            long hours = TimeUnit.MILLISECONDS.toHours(loadedEvent.time - current);
                            long minutes = TimeUnit.MILLISECONDS.toMinutes(loadedEvent.time - current) - TimeUnit.HOURS.toMinutes(hours);
                            eventStatus = "Starts in " + String.valueOf(hours) + " hours, " + String.valueOf(minutes) + " minutes";
                        }
                        else if (current > loadedEvent.time + loadedEvent.duration) { //Already finished
                            long hours = TimeUnit.MILLISECONDS.toHours(current - loadedEvent.time - loadedEvent.duration);
                            long minutes = TimeUnit.MILLISECONDS.toMinutes(current - loadedEvent.time - loadedEvent.duration) - TimeUnit.HOURS.toMinutes(hours);
                            eventStatus = "Ended " + String.valueOf(hours) + " hours, " + String.valueOf(minutes) + " minutes ago";
                        }
                        infoDialog.setMessage("Event Name: " + loadedEvent.name + "\nEvent Owner: " + loadedEvent.username + "\n" + eventStatus + "\nAttendance: " + String.valueOf(dataSnapshot.getChildrenCount()));
                        Log.v(TAG, "Child count: " + String.valueOf(dataSnapshot.getChildrenCount()));
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {

                    }
                };
                infoDialog.setOnCancelListener(new DialogInterface.OnCancelListener() { //Stop listening for attendance updates if the dialog isn't open
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        attendance.child(marker.getTitle()).removeEventListener(attendanceListener);
                        comments.child(marker.getTitle()).removeEventListener(commentListener);
                    }
                });
                infoDialog.show();
                Log.v(TAG, "In the marker click listener, title is " + marker.getTitle());
                attendance.child(marker.getTitle()).addValueEventListener(attendanceListener);
                comments.child(marker.getTitle()).addChildEventListener(commentListener);
                return true;
            }
        };
    }

    private static class LatLngEvaluator implements TypeEvaluator<LatLng> { //Used to interpolate between points when we animate the marker moving on event creation
        private LatLng latLng = new LatLng();

        @Override
        public LatLng evaluate(float fraction, LatLng startValue, LatLng endValue) {
            latLng.setLatitude(startValue.getLatitude() + ((endValue.getLatitude() - startValue.getLatitude()) * fraction));
            latLng.setLongitude(startValue.getLongitude() + ((endValue.getLongitude() - startValue.getLongitude()) * fraction));
            return latLng;
        }
    }

    private PolylineOptions drawCircle(LatLng position, int color, double radiusMeters) { //Draws the circle based around a given position
        PolylineOptions polyopt = new PolylineOptions();
        polyopt.color(color);
        polyopt.width(0.5f);
        polyopt.addAll(getCirclePoints(position, radiusMeters));
        return polyopt;
    }

    private static ArrayList<LatLng> getCirclePoints(LatLng position, double radius) { //Interpolates points to form a polyline to represent a circle
        int degreesBetweenPoints = 10; // change here for shape
        int numberOfPoints = (int) Math.floor(360 / degreesBetweenPoints);
        double distRadians = radius / 6371000.0; // earth radius in meters
        double centerLatRadians = Math.toRadians(position.getLatitude());
        double centerLonRadians = Math.toRadians(position.getLongitude());
        ArrayList<LatLng> polygons = new ArrayList<>(); // array to hold all the points
        for (int index = 0; index < numberOfPoints; index++) {
            double degrees = index * degreesBetweenPoints;
            double degreeRadians = Math.toRadians(degrees);
            double pointLatRadians = Math.asin(Math.sin(centerLatRadians) * Math.cos(distRadians)
                    + Math.cos(centerLatRadians) * Math.sin(distRadians) * Math.cos(degreeRadians));
            double pointLonRadians = centerLonRadians + Math.atan2(Math.sin(degreeRadians)
                            * Math.sin(distRadians) * Math.cos(centerLatRadians),
                    Math.cos(distRadians) - Math.sin(centerLatRadians) * Math.sin(pointLatRadians));
            double pointLat = Math.toDegrees(pointLatRadians);
            double pointLon = Math.toDegrees(pointLonRadians);
            LatLng point = new LatLng(pointLat, pointLon);
            polygons.add(point);
        }
        // add first point at end to close circle
        polygons.add(polygons.get(0));
        return polygons;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) { //Handles permissions requests + initializes afterwards
        switch (requestCode) {
            case REQUEST_CODE_ASK_PERMISSIONS:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission Granted
                    Toast.makeText(MapsActivity.this, "Permission Granted", Toast.LENGTH_SHORT)
                            .show();
                    initializeLocationEngine();
                    locationPlugin = new LocationLayerPlugin(mapView, mapboxMap, locationEngine);
                    locationPlugin.setRenderMode(RenderMode.COMPASS);
                    locationPlugin.setCameraMode(CameraMode.TRACKING_COMPASS);
                } else {
                    // Permission Denied
                    Toast.makeText(MapsActivity.this, "Permission Denied", Toast.LENGTH_SHORT)
                            .show();
                }
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    public void deleteCommentClickHandler(View v) { //Used when the comment owner or event owner chooses to delete a comment
        Log.v(TAG, "In deletecommentclickhandler");
        Comment comment = (Comment)v.getTag();
        comments.child(comment.event).child(String.valueOf(comment.hash)).removeValue(); //Delete it, triggers the listener to update the list
    }

    public void pinCommentClickHandler(View v) { //Used when the event owner chooses to pin a comment
        Log.v(TAG, "In pincommentclickhandler");
        Comment comment = (Comment)v.getTag();
        if (comment.pin == 1) { //If it's pinned, unpin it - the view refresh will change the button appropriately
            comment.pin = 0;
        }
        else { //pin it
         comment.pin = 1;
        }
        comments.child(comment.event).child(String.valueOf(comment.hash)).setValue(comment); //update the value in the database to trigger a refresh/move it in the list

    }

    @Override
    @SuppressWarnings({"MissingPermission"})
    protected void onStart() {
        Log.v(TAG, "In onstart");
        super.onStart();
        if (locationEngine != null) {
            locationEngine.requestLocationUpdates();
        }
        if (locationPlugin != null) {
            locationPlugin.onStart();
        }
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        Log.v(TAG, "In onresume");
        super.onResume();
        mapView.onResume();
        if (locationEngine != null) {
            locationEngine.requestLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        Log.v(TAG, "In onpause");
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onStop() {
        Log.v(TAG, "In onstop");
        super.onStop();
        if (locationEngine != null) {
            locationEngine.removeLocationUpdates();
        }
        if (locationPlugin != null) {
            locationPlugin.onStop();
        }
        if (attendingEventHash != 0) {
            attendance.child(String.valueOf(attendingEventHash)).child(uid).removeValue(); //Remove the pings that we had been keeping track of
            attendingEventHash = 0;
        }
        mapView.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapView.onDestroy();
        if (locationEngine != null) {
            locationEngine.deactivate();
        }
        if (attendingEventHash != 0) {
            attendance.child(String.valueOf(attendingEventHash)).child(uid).removeValue(); //Remove the pings that we had been keeping track of
            attendingEventHash = 0;
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }
}