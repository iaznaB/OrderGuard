package com.example.orderguard

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.*
import java.util.*

class SheetsManager(private val context: Context) {

    fun createSheet(account: GoogleSignInAccount) {
        Thread {
            try {
                val credential = GoogleAccountCredential.usingOAuth2(
                    context,
                    listOf(
                        "https://www.googleapis.com/auth/spreadsheets",
                        "https://www.googleapis.com/auth/drive.file"
                    )
                )
                credential.selectedAccount = account.account

                val sheetsService = Sheets.Builder(
                    AndroidHttp.newCompatibleTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential
                )
                    .setApplicationName("OrderGuard")
                    .build()

                // Check if we already created a sheet
                val prefs = context.getSharedPreferences("SheetPrefs", Context.MODE_PRIVATE)
                if (prefs.contains("spreadsheet_id")) {
                    Log.d("SheetsManager", "Sheet already exists")
                    return@Thread
                }

                val spreadsheet = Spreadsheet()
                    .setProperties(
                        SpreadsheetProperties().setTitle("OrderGuard_History")
                    )

                val createdSheet = sheetsService.spreadsheets()
                    .create(spreadsheet)
                    .execute()

                val spreadsheetId = createdSheet.spreadsheetId

                // Save spreadsheet ID locally
                prefs.edit().putString("spreadsheet_id", spreadsheetId).apply()

                Log.d("SheetsManager", "Created sheet with ID: $spreadsheetId")

                // Add headers
                val values = listOf(
                    listOf(
                        "Date",
                        "Time",
                        "App",
                        "Price ($)",
                        "Miles",
                        "Avg $/M",
                        "Status",
                        "Business Name",
                        "Pick-up Address",
                        "Drop-off Address",
                        "Est. Time",
                        "Actual Time",
                        "Actual Miles"
                    )
                )

                val body = ValueRange().setValues(values)

                sheetsService.spreadsheets().values()
                    .append(spreadsheetId, "Sheet1!A1", body)
                    .setValueInputOption("RAW")
                    .setInsertDataOption("INSERT_ROWS")
                    .execute()

            } catch (e: Exception) {
                Log.e("SheetsManager", "Error creating sheet: ${e.message}")
            }
        }.start()
    }

    fun logOrderToSheet(
        date: String,
        time: String,
        appName: String,
        price: Double,
        miles: Double,
        status: String,
        businessName: String = "",
        pickupAddress: String = "",
        dropoffAddress: String = "",
        estTime: String = "",
        actualTime: String = "",
        actualMiles: String = ""
    ) {
        Thread {
            try {
                val account = com.google.android.gms.auth.api.signin.GoogleSignIn
                    .getLastSignedInAccount(context) ?: return@Thread

                val prefs = context.getSharedPreferences("SheetPrefs", Context.MODE_PRIVATE)
                val spreadsheetId = prefs.getString("spreadsheet_id", null) ?: return@Thread

                val credential = GoogleAccountCredential.usingOAuth2(
                    context,
                    listOf(
                        "https://www.googleapis.com/auth/spreadsheets",
                        "https://www.googleapis.com/auth/drive.file"
                    )
                )
                credential.selectedAccount = account.account

                val sheetsService = Sheets.Builder(
                    AndroidHttp.newCompatibleTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential
                )
                    .setApplicationName("OrderGuard")
                    .build()

                // If final status, remove DETECTED row with same date/time/app
                if (status.uppercase() != "DETECTED") {

                    val existing = sheetsService.spreadsheets().values()
                        .get(spreadsheetId, "Sheet1!A2:M")
                        .execute()

                    val rows = existing.getValues() ?: emptyList()

                    val requests = mutableListOf<Request>()

                    for (i in rows.indices) {
                        val row = rows[i]
                        if (row.size < 6) continue

                        val rDate = row[0].toString()
                        val rTime = row[1].toString()
                        val rApp = row[2].toString()
                        val rStatus = row.getOrNull(6)?.toString() ?: ""

                        if (
                            rDate == date &&
                            rTime == time &&
                            rApp == appName &&
                            rStatus.equals("DETECTED", true)
                        ) {

                            val deleteRequest = Request().setDeleteDimension(
                                DeleteDimensionRequest()
                                    .setRange(
                                        DimensionRange()
                                            .setSheetId(0)
                                            .setDimension("ROWS")
                                            .setStartIndex(i + 1)
                                            .setEndIndex(i + 2)
                                    )
                            )

                            requests.add(deleteRequest)
                        }
                    }

                    if (requests.isNotEmpty()) {
                        val batch = BatchUpdateSpreadsheetRequest().setRequests(requests)

                        sheetsService.spreadsheets()
                            .batchUpdate(spreadsheetId, batch)
                            .execute()
                    }
                    val avgPerMile =
                        if (miles > 0) String.format(Locale.US, "%.2f", price / miles) else ""

                    val values = listOf(
                        listOf(
                            date,
                            time,
                            appName,
                            price.toString(),
                            miles.toString(),
                            avgPerMile,
                            status,
                            businessName,
                            pickupAddress,
                            dropoffAddress,
                            estTime,
                            actualTime,
                            actualMiles
                        )
                    )

                    val body = ValueRange().setValues(values)

                    sheetsService.spreadsheets().values()
                        .append(spreadsheetId, "Sheet1!A:M", body)
                        .setValueInputOption("RAW")
                        .setInsertDataOption("INSERT_ROWS")
                        .execute()
                }

            } catch (e: Exception) {
                Log.e("SheetsManager", "Error logging order: ${e.message}")
            }
        }.start()
    }

    fun hasRequiredPermissions(): Boolean {
        val account = com.google.android.gms.auth.api.signin.GoogleSignIn
            .getLastSignedInAccount(context)

        return account != null
    }

    fun getOrdersForDate(date: String): List<Map<String, String>> {
        try {
            val account = com.google.android.gms.auth.api.signin.GoogleSignIn
                .getLastSignedInAccount(context) ?: return emptyList()

            val prefs = context.getSharedPreferences("SheetPrefs", Context.MODE_PRIVATE)
            val spreadsheetId = prefs.getString("spreadsheet_id", null) ?: return emptyList()

            val credential = GoogleAccountCredential.usingOAuth2(
                context,
                listOf(
                    "https://www.googleapis.com/auth/spreadsheets",
                    "https://www.googleapis.com/auth/drive.file"
                )
            )
            credential.selectedAccount = account.account

            val sheetsService = Sheets.Builder(
                AndroidHttp.newCompatibleTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            )
                .setApplicationName("OrderGuard")
                .build()

            val response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, "Sheet1!A2:M")
                .execute()

            val rows = response.getValues() ?: return emptyList()

            val results = mutableListOf<Map<String, String>>()

            for (row in rows) {
                if (row.size < 6) continue

                if (row[0] == date) {
                    results.add(
                        mapOf(
                            "Date" to row.getOrNull(0).toString(),
                            "Time" to row.getOrNull(1).toString(),
                            "App" to row.getOrNull(2).toString(),
                            "Price ($)" to row.getOrNull(3).toString(),
                            "Miles" to row.getOrNull(4).toString(),
                            "Avg $/M" to row.getOrNull(5).toString(),
                            "Status" to row.getOrNull(6).toString(),
                            "Business Name" to row.getOrNull(7).toString(),
                            "Pick-up Address" to row.getOrNull(8).toString(),
                            "Drop-off Address" to row.getOrNull(9).toString(),
                            "Est. Time" to row.getOrNull(10).toString(),
                            "Actual Time" to row.getOrNull(11).toString(),
                            "Actual Miles" to row.getOrNull(12).toString()
                        )
                    )
                }
            }

            return results

        } catch (e: Exception) {
            Log.e("SheetsManager", "Error reading sheet: ${e.message}")
            return emptyList()
        }
    }
}