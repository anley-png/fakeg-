# Mock Route (simple Android app)

Plays a GPX route as mock locations. You choose an area (Area 1 to 4), press START, and it sends each point at its time stamp.

- Choose Area: loads that area's route (bundled in the app) and fills accuracy min/max, seconds between points and the repeat waits. You can still edit the numbers.
  The numbers per area are in the `AREAS` list at the top of `MainActivity.kt`; the routes are in `app/src/main/assets/area1.gpx` ... `area4.gpx`.
- "Use a different GPX file" is still there if you want your own route.
Altitude, speed and bearing come from the GPX.

- Accuracy drifts between the two numbers you type: a small random step (up to 1 m) on every update, and now and then a bigger step of 3 to 6 m.
- Between two route points the location is repeated at random waits (default 1 to 5 seconds), so the time stamp keeps changing and the location age varies.
  Set the shortest wait to 0 to send a location only at each point.
- Hop counter: "Hop 12 / 500" (and the round number) is shown in the notification only. Nothing is drawn over the screen.
- Loop (on by default): after the last point it walks straight back to the first point at the route's own speed, then starts again until you press STOP.

## Get the APK (no Android Studio needed)
1. Make a free account at github.com and create a new repository (any name).
2. Upload everything from this folder to the repository (Add file > Upload files).
   If the `.github` folder does not upload, choose Add file > Create new file, type the name
   `.github/workflows/build.yml`, and paste the contents of that file.
3. Open the Actions tab > "Build APK" > Run workflow. Wait about 5 minutes.
4. Open the finished run, download the `MockRoute-apk` file, unzip it, and install `app-debug.apk` on the phone.

## Use it
1. Phone: Settings > About phone > tap Build number 7 times.
2. Settings > Developer options > Select mock location app > Mock Route.
3. Open Mock Route, allow location and notifications, choose the area, press START.
4. Press STOP when you are done.
