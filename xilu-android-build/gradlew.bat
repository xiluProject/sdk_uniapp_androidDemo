@echo off
rem Use cached Gradle 8.13 with JDK 17 (no wrapper download)
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
set "GRADLE_HOME=C:\Users\38017\.gradle\wrapper\dists\gradle-8.13-bin\ap7pdhvhnjtc6mxtzz89gkh0c\gradle-8.13"
call "%GRADLE_HOME%\bin\gradle.bat" %*
