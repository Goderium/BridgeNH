package appeng.api;

/** Public factory-entry fixture; configured only by the mock self-test. */
public final class AEApi {
    public static IAppEngApi api;
    public static IAppEngApi instance() { return api; }
}
