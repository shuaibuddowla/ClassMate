$env:JAVA_HOME = "C:\Program Files\Android\Android Studio2\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& ".\gradlew.bat" :app:assembleRelease -PclassmateTarget=production --no-build-cache
