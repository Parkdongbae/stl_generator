@echo off
title STL Generator
cd /d "%~dp0"
if not exist "StlGenerator.class" (
  echo Compiling StlGenerator.java ...
  javac -encoding UTF-8 StlGenerator.java
)
chcp 65001 >nul
java StlGenerator
pause
