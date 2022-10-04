package com.example.em_ble_bridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DevicesAdapter (private val devices: List<DetectedDevice>) :
    RecyclerView.Adapter<DevicesAdapter.ViewHolder>() {
    // Provide a direct reference to each of the views within a data item
    // Used to cache the views within the item layout for fast access
    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Your holder should contain and initialize a member variable
        // for any view that will be set as you render a row
        val friendlyNameTextView: TextView = itemView.findViewById(R.id.tvFriendlyName)
        val macTextView: TextView = itemView.findViewById(R.id.tvMac)
        val timestampTextView: TextView = itemView.findViewById(R.id.tvTimestamp)
        val rssiTextView: TextView = itemView.findViewById(R.id.tvRssi)
    }
    // ... constructor and member variables
    // Usually involves inflating a layout from XML and returning the holder
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DevicesAdapter.ViewHolder {
        val context = parent.context
        val inflater = LayoutInflater.from(context)
        // Inflate the custom layout
        val devicesView = inflater.inflate(R.layout.item_beacon, parent, false)
        // Return a new holder instance
        return ViewHolder(devicesView)
    }

    // Involves populating data into the item through holder
    override fun onBindViewHolder(viewHolder: DevicesAdapter.ViewHolder, position: Int) {
        // Get the data model based on position
        val device: DetectedDevice = devices.get(position)
        // Set item views based on your views and data model
        val friendlyName = viewHolder.friendlyNameTextView
        friendlyName.text = device.friendlyName
        val mac = viewHolder.macTextView
        mac.text = device.beaconMac
        val ts = viewHolder.timestampTextView
        ts.text = "Last seen: ${device.timestamp}"
        val rssi = viewHolder.rssiTextView
        rssi.text = "${device.rssi} dbm"
    }

    // Returns the total count of items in the list
    override fun getItemCount(): Int {
        return devices.size
    }
}
