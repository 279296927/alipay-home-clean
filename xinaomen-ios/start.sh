#!/system/bin/sh
# 新澳门 PWA 本地服务：在安卓设备上跑 HTTP 服务，供 iPhone/任何设备用浏览器访问
# 用法：sh start.sh          （默认端口 8899）
#      sh start.sh 9000     （自定义端口）
PORT="${1:-8899}"
DIR="/sdcard/Download/solab/XinAomen/ios"

if ! command -v python3 >/dev/null 2>&1; then
  echo "需要 python3（Ubuntu 终端环境已内置）"
  exit 1
fi

IP=$(ip -4 addr 2>/dev/null | grep -o 'inet [0-9.]*' | awk '{print $2}' | grep -v '^127\.' | head -1)
[ -z "$IP" ] && IP="<设备IP>"

echo "==================================================="
echo " 新澳门 PWA 服务已启动"
echo " 目录：$DIR"
echo ""
echo " iPhone / iPad 用 Safari 打开："
echo "   http://$IP:$PORT/index.html"
echo ""
echo " 打开后点「分享 → 添加到主屏幕」，即成桌面 App"
echo " 停止服务：Ctrl + C"
echo "==================================================="
cd "$DIR" || exit 1
exec python3 -m http.server "$PORT" --bind 0.0.0.0
