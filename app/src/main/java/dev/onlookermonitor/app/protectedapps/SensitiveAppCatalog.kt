package dev.onlookermonitor.app.protectedapps

/**
 * Starter list of India-relevant "looks sensitive" apps, matched by package name where installed,
 * used only to *pre-check* rows in the Protected Apps picker. It is one input to the pre-check
 * heuristic (the other is whether an app requests a biometric permission); the user can freely
 * add or remove anything afterwards.
 *
 * ---------------------------------------------------------------------------------------------
 * EDIT HERE. This list is expected to change over time and may be maintained by someone other
 * than the original author. Keep it a flat set of package names, grouped by the comments below.
 * Package names are best-effort — an entry that does not match any installed app is simply
 * ignored (no crash, no effect), so err on the side of including plausible variants.
 * ---------------------------------------------------------------------------------------------
 */
object SensitiveAppCatalog {

    val PACKAGES: Set<String> = setOf(
        // --- UPI / wallets / payments ---
        "com.google.android.apps.nbu.paisa.user", // Google Pay (India)
        "com.phonepe.app",                        // PhonePe
        "net.one97.paytm",                        // Paytm
        "in.org.npci.upiapp",                     // BHIM
        "in.amazon.mShop.android.shopping",       // Amazon (Amazon Pay) - Ae/India build
        "com.amazon.mShop.android.shopping",      // Amazon (Amazon Pay)
        "com.dreamplug.androidapp",               // CRED
        "com.mobikwik_new",                       // MobiKwik
        "com.freecharge.android",                 // Freecharge

        // --- Banks ---
        "com.snapwork.hdfc",                      // HDFC Bank
        "com.sbi.lotusintouch",                   // SBI YONO
        "com.sbi.SBIFreedomPlus",                 // SBI Anywhere / YONO Lite
        "com.csam.icici.bank.imobile",            // ICICI iMobile Pay
        "com.axis.mobile",                        // Axis Mobile
        "com.msf.kbank.mobile",                   // Kotak
        "com.bankofbaroda.mconnect",              // BoB World
        "com.canarabank.mobility",                // Canara ai1
        "com.YESBANK",                            // YES Bank
        "com.fss.pnbpsp",                         // PNB (BHIM PNB)
        "com.infrasofttech.CentralBank",          // Central Bank

        // --- Government / identity / investing ---
        "in.gov.uidai.mAadhaarPlus",              // mAadhaar
        "com.digilocker.android",                 // DigiLocker
        "com.nextbillion.groww",                  // Groww
        "com.zerodha.kite3",                      // Zerodha Kite
        "in.gov.incometax.aisapp",                // Income Tax (AIS)
    )
}
