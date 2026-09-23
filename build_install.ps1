$env:JAVA_HOME = "C:\Program Files\Android\Android Studio2\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& ".\gradlew.bat" installDebug 2>&1 | Out-File -Encoding utf8 install_out.txt
