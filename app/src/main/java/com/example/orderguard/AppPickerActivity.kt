package com.example.orderguard

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText

class AppPickerActivity : ComponentActivity() {

    private lateinit var adapter: AppPickerAdapter
    private val selectedPackages = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_app_picker)

        val recyclerView = findViewById<RecyclerView>(R.id.appRecyclerView)
        val searchBar = findViewById<TextInputEditText>(R.id.searchBar)
        val okButton = findViewById<Button>(R.id.okButton)
        val cancelButton = findViewById<Button>(R.id.cancelButton)
        val appCountText = findViewById<TextView>(R.id.appCountText)

        val prefs = getSharedPreferences("OrderGuardPrefs", MODE_PRIVATE)

        selectedPackages.addAll(
            prefs.getStringSet("ALLOWED_RETURN_APPS", emptySet()) ?: emptySet()
        )

        val pm = packageManager

        val apps = pm.getInstalledApplications(0)
            .filter {
                pm.getLaunchIntentForPackage(it.packageName) != null &&
                        (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        appCountText.text = "${apps.size} apps"

        adapter = AppPickerAdapter(this, apps, selectedPackages)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

// Add subtle Samsung-style dividers
        recyclerView.addItemDecoration(
            androidx.recyclerview.widget.DividerItemDecoration(
                this,
                androidx.recyclerview.widget.DividerItemDecoration.VERTICAL
            )
        )

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = rv.layoutManager as LinearLayoutManager
                val pos = layoutManager.findFirstVisibleItemPosition()
                adapter.getSectionLetter(pos)
            }
        })

        searchBar.addTextChangedListener {
            adapter.filter(it.toString())
        }

        okButton.setOnClickListener {
            prefs.edit {
                putStringSet("ALLOWED_RETURN_APPS", selectedPackages)
            }
            finish()
        }

        cancelButton.setOnClickListener {
            finish()
        }
    }
}