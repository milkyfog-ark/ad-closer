package jp.milkyfog.adcloser;

// User service interface for the Shizuku tap engine (v2.55).
// Implemented by ShizukuTapService, which runs with shell
// identity inside Shizuku's process space.
interface IShizukuTap {
    // The aidl compiler requires ids on ALL methods or none;
    // destroy keeps Shizuku's conventional id.
    void tap(int x, int y, int durationMs) = 1;
    String diag() = 2;
    void destroy() = 16777114;
}
