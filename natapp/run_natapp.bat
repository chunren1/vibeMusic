@echo  off
:: natapp 启动脚本（由安装程序自动生成）
:: 双击此文件即可启动 natapp 隧道
::
:: 加入系统服务（开机自启）参考: "D:\vibeMusic\natapp\natapp.exe" -authtoken=7d7662ee4c96dbf0 -service help
:: 运行多条隧道: 仿照此文件，将 authtoken 替换为其他隧道的 token，另存为新文件运行
"D:\vibeMusic\natapp\natapp.exe" -authtoken=7d7662ee4c96dbf0
pause