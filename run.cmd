@echo off
start "AI Token Usage" /min powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "%~dp0run.ps1"
