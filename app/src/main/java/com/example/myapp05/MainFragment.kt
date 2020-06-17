package com.example.myapp05

import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.esri.arcgisruntime.ArcGISRuntimeEnvironment
import com.esri.arcgisruntime.geometry.*
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.mapping.Basemap
import com.esri.arcgisruntime.mapping.view.DefaultMapViewOnTouchListener
import com.esri.arcgisruntime.mapping.view.Graphic
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.esri.arcgisruntime.mapping.view.MapView
import com.esri.arcgisruntime.symbology.PictureMarkerSymbol
import com.esri.arcgisruntime.symbology.SimpleFillSymbol
import com.esri.arcgisruntime.symbology.SimpleLineSymbol
import com.esri.arcgisruntime.symbology.SimpleMarkerSymbol
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.gson.responseObject
import java.lang.Boolean.TRUE
import java.util.concurrent.ExecutionException
import kotlin.math.roundToInt


/**
 * A simple [Fragment] subclass.
 */
class MainFragment : Fragment() {

    private lateinit var mMapView: MapView
    private lateinit var animateContainer: LinearLayout

    data class Bus(var id: Int, var lat: Double, var lon: Double, var name: String, var color: String)
    data class BusTracker(var success: Boolean = false, var data: List<Bus>)

    private lateinit var busLayer: GraphicsOverlay //เก็บตำแหน่งรถบัส
    private lateinit var busStopLayer: GraphicsOverlay //เก็บตำแหน่งที่จอบรถบัส
    private lateinit var bufferLayer:GraphicsOverlay //เก็บรัศมีของป้าย

    private lateinit var activeBtn: Button
    private var isActivate: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mMapView = view.findViewById<MapView>(R.id.mapView);
        animateContainer = view.findViewById<LinearLayout>(R.id.animateContainer);
        activeBtn = view.findViewById<Button>(R.id.activeBtn)
        setupMap();

        Handler().postDelayed(Runnable {
            mMapView.visibility = View.VISIBLE;
            activeBtn.visibility = View.VISIBLE
            animateContainer.visibility = View.GONE;
        }, 3000)

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post(object : Runnable {
            override fun run() {
                requestToServer()
                mainHandler.postDelayed(this, 4000) // call again 4 sec later
            }
        })

        activeBtn.setOnClickListener{
            if (activeBtn.text == "Cancel") {
                isActivate = false
                bufferLayer.graphics.clear()
                activeBtn.text = "Select Bus Stop"
            }
            else {
                activeBtn.text = "Cancel"
                isActivate = true

            }
        }

    }

    fun requestToServer() {
        Fuel.get("https://us-central1-covid-273105.cloudfunctions.net/getTracker", listOf("id" to ""))
            .responseObject<BusTracker> { request, response, result ->
                val (busList, error) = result
                busList?.data?.forEach {
                    addOrUpdateBus(it)
                }
                Log.d("MY_TAG", busList.toString())
            }


    }

    fun addOrUpdateBus(busItem: Bus) {
        val newPoint = Point(busItem.lon, busItem.lat, SpatialReferences.getWgs84())
        val color = busItem.let{ if (it.color == "red") Color.RED else Color.BLUE }
        val redCircleSymbol = SimpleMarkerSymbol(SimpleMarkerSymbol.Style.CIRCLE, color, 10F)

        // find exist graphic
        val existGraphic = busLayer.graphics.find { it.attributes["id"] == busItem.id }
        if (existGraphic != null) {
            // update geometry
            existGraphic.geometry = newPoint
        } else {
            // insert new if not exist
            val newGraphic = Graphic(newPoint, redCircleSymbol)
            val attribute = mutableMapOf<String, Any>()
            attribute["id"] = busItem.id
            attribute["color"] = busItem.color
            attribute["name"] = busItem.name
            newGraphic.attributes.putAll(attribute)
            busLayer.graphics.add(newGraphic)


        }
    }

    private fun setupMap() {
        ArcGISRuntimeEnvironment.setLicense(resources.getString(R.string.arcgis_license_key))
        val basemapType = Basemap.Type.TOPOGRAPHIC
        val latitude = 13.700547
        val longitude = 100.535619
        val levelOfDetail = 15
        val map = ArcGISMap(basemapType, latitude, longitude, levelOfDetail)



        mMapView.onTouchListener = object : DefaultMapViewOnTouchListener(context, mMapView) {
            override fun onSingleTapConfirmed(motionEvent: MotionEvent): Boolean {
                // get the point that was clicked and convert it to a point in map coordinates
                val screenPoint = android.graphics.Point(
                    motionEvent.x.roundToInt(),
                    motionEvent.y.roundToInt()
                )
                // create a map point from screen point
                val mapPoint = mMapView.screenToLocation(screenPoint)
                val wgs84Point = GeometryEngine.project(mapPoint, SpatialReferences.getWgs84()) as Point
                Log.d("MyTag", "onSingleTapConfirmed: $wgs84Point")
                // Get Clicked Point, Then sent that point to search graphic on top of it.
                val identifyTask = mMapView.identifyGraphicsOverlayAsync(busStopLayer, screenPoint, 10.0, false, 10)
                identifyTask.addDoneListener(){
                    try {
                        // get the list of graphics returned by identify
                        val identifiedGraphics: List<Graphic> = identifyTask.get().graphics
                        if (identifiedGraphics.count() > 0 && isActivate) {
                            bufferLayer.graphics.clear()
                            // get clicked graphic
                            val selectGraphic = identifiedGraphics[0]

                            // create buffer geometry 100 meter
                            val geometryBuffer = GeometryEngine.bufferGeodetic(selectGraphic.geometry, 100.0,
                                LinearUnit(LinearUnitId.METERS), Double.NaN, GeodeticCurveType.GEODESIC)

                            // create symbol for buffer geometry
                            val geodesicOutlineSymbol = SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, Color.BLACK, 2F)
                            // 0x4D00FF00 = Green Color with 25% Opacity (4D = 25%)
                            val geodesicBufferFillSymbol = SimpleFillSymbol(SimpleFillSymbol.Style.SOLID,
                                0x4D00FF00.toInt(), geodesicOutlineSymbol)

                            // new graphic
                            val graphicBuffer =  Graphic(geometryBuffer, geodesicBufferFillSymbol)
                            bufferLayer.graphics.add(graphicBuffer)







                        }
                    } catch (ex: InterruptedException) {
                        Log.d("MyTag", "InterruptedException: ${ex.message}")
                    } catch (ex: ExecutionException) {
                        Log.d("MyTag", "ExecutionException: ${ex.message}")
                    }
                }
                return true
            }
        }

        mMapView.isAttributionTextVisible = false
        mMapView.map = map

        bufferLayer = GraphicsOverlay()
        mMapView.graphicsOverlays.add(bufferLayer)

        busStopLayer = GraphicsOverlay()
        mMapView.graphicsOverlays.add(busStopLayer)

        busLayer = GraphicsOverlay()
        mMapView.graphicsOverlays.add(busLayer)

        addBusStop(13.692557, 100.533836)
        addBusStop(13.702700, 100.533976)

    }

    fun addBusStop(lat: Double, long: Double, attributes: MutableMap<String, Any>? = null) {
        // Create new Point
        val newPoint = Point(long, lat, SpatialReferences.getWgs84())
        // Get Picture
        val confirmDrawable = ContextCompat.getDrawable(context!!, R.drawable.bus_stop) as BitmapDrawable?
        try {
            // Create Picture Symbol
            val pinSourceSymbol: PictureMarkerSymbol = PictureMarkerSymbol.createAsync(confirmDrawable).get()
            pinSourceSymbol.height = 18F;
            pinSourceSymbol.width = 18F;
            // Load Picture
            pinSourceSymbol.loadAsync()
            // Set Callback
            pinSourceSymbol.addDoneLoadingListener {
                // When Picture is loaded,
                // Create New Graphic with Picture Symbol
                val newGraphic = Graphic(newPoint, pinSourceSymbol)
                // Add Attribute to Graphic
                if (attributes != null) {
                    newGraphic.attributes.putAll(attributes)
                }
                // Add to Graphic Overlay Layer
                busStopLayer.graphics.add(newGraphic)
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
        } catch (e: ExecutionException) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        mMapView.pause();
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        mMapView.resume();
    }

    override fun onDestroy() {
        mMapView.dispose();
        super.onDestroy()
    }

}
