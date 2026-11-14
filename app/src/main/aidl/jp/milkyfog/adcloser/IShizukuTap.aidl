package jp.milkyfog.adcloser;

// User service interface for the Shizuku tap engine (v2.55).
// Implemented by ShizukuTapService, which runs with shell
// identity inside Shizuku's process space.
interface IShizukuTap {
    void tap(int x, int y, int durationMs);
    void destroy() = 16777114;
}
