package com.example.orderguard

import com.google.android.material.materialswitch.MaterialSwitch
import androidx.recyclerview.widget.DiffUtil
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppPickerAdapter(
    private val context: Context,
    apps: List<ApplicationInfo>,
    private val selectedPackages: MutableSet<String>
) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

    fun getSectionLetter(position: Int): Char {

        if (position < 0 || position >= filteredApps.size) return '#'

        val name = pm.getApplicationLabel(filteredApps[position]).toString()

        return name.first().uppercaseChar()
    }

    private val pm: PackageManager = context.packageManager

    private val originalApps = apps
    private var filteredApps = apps.toMutableList()

    private val iconCache = HashMap<String, Drawable>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val checkBox: MaterialSwitch = view.findViewById(R.id.checkBox)
        val header: TextView = view.findViewById(R.id.sectionHeader)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.item_app, parent, false)

        return ViewHolder(view)
    }

    override fun getItemCount(): Int = filteredApps.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val app = filteredApps[position]
        val packageName = app.packageName

        val appName = pm.getApplicationLabel(app).toString()
        holder.name.text = appName

        val cachedIcon = iconCache[packageName]

        if (cachedIcon != null) {
            holder.icon.setImageDrawable(cachedIcon)
        } else {
            holder.icon.post {
                val icon = pm.getApplicationIcon(app)
                iconCache[packageName] = icon
                holder.icon.setImageDrawable(icon)
            }
        }

        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = selectedPackages.contains(packageName)

        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedPackages.add(packageName)
            } else {
                selectedPackages.remove(packageName)
            }
        }

        val currentLetter = appName.first().uppercaseChar()

        val previousLetter = if (position > 0) {
            pm.getApplicationLabel(filteredApps[position - 1])
                .toString()
                .first()
                .uppercaseChar()
        } else null

        if (previousLetter == null || currentLetter != previousLetter) {
            holder.header.visibility = View.VISIBLE
            holder.header.text = currentLetter.toString()
        } else {
            holder.header.visibility = View.GONE
        }
    }

    fun filter(query: String) {

        val newList = if (query.isEmpty()) {
            originalApps.toMutableList()
        } else {
            originalApps.filter {
                pm.getApplicationLabel(it)
                    .toString()
                    .lowercase()
                    .contains(query.lowercase())
            }.toMutableList()
        }

        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {

            override fun getOldListSize(): Int = filteredApps.size

            override fun getNewListSize(): Int = newList.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return filteredApps[oldItemPosition].packageName ==
                        newList[newItemPosition].packageName
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return filteredApps[oldItemPosition].packageName ==
                        newList[newItemPosition].packageName
            }
        })

        filteredApps = newList

        diffResult.dispatchUpdatesTo(this)
    }
}