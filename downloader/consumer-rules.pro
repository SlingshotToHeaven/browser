# libffmpegkit.so resolves the class and these members by their original JNI names.
# The private upcalls are invisible to R8's Java call graph.
-keep class com.arthenica.ffmpegkit.FFmpegKitConfig {
    native <methods>;
    private static void log(long,int,byte[]);
    private static void statistics(long,int,float,float,long,double,double,double);
    private static int safOpen(int);
    private static int safClose(int);
}
