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
                    listOf("Date", "Time", "App", "Price ($)", "Miles", "Status")
                )

                val body = ValueRange().setValues(values)

                sheetsService.spreadsheets().values()
                    .update(spreadsheetId, "Sheet1!A1", body)
                    .setValueInputOption("RAW")
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
        status: String
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

                val values = listOf(
                    listOf(date, time, appName, price, miles, status)
                )

                val body = ValueRange().setValues(values)

                sheetsService.spreadsheets().values()
                    .append(spreadsheetId, "Sheet1!A1", body)
                    .setValueInputOption("RAW")
                    .execute()

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
                .get(spreadsheetId, "Sheet1!A2:F")
                .execute()

            val rows = response.getValues() ?: return emptyList()

            val results = mutableListOf<Map<String, String>>()

            for (row in rows) {
                if (row.size < 6) continue

                if (row[0] == date) {
                    results.add(
                        mapOf(
                            "Date" to row[0].toString(),
                            "Time" to row[1].toString(),
                            "App" to row[2].toString(),
                            "Price ($)" to row[3].toString(),
                            "Miles" to row[4].toString(),
                            "Status" to row[5].toString()
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