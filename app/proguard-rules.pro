# Glance persists widget class names. Preserve their identity and public no-arg
# constructors across unoptimized/optimized upgrades and later R8 mappings.
# Room, WorkManager and Glance ActionCallback reflection rules come from their
# libraries' consumer rules; avoid retaining whole application/library packages.
-keep,allowoptimization class io.github.goooseby.tickmark.widget.TodayWidget {
    public <init>();
}
-keep,allowoptimization class io.github.goooseby.tickmark.widget.AwayWidget {
    public <init>();
}
