#!/usr/bin/env bash
# TripPlanner Testing Agent — one-time setup
set -e

echo "📦  Installing Python dependencies..."
pip install -r requirements.txt

echo "🌐  Installing Playwright Chromium browser..."
playwright install chromium

echo ""
echo "✅  Setup complete!"
echo ""
echo "Usage:"
echo "  export ANTHROPIC_API_KEY=sk-ant-..."
echo "  python agent.py"
echo ""
echo "Options:"
echo "  python agent.py --prompt 'Test only the booking flow'"
echo "  python agent.py --backend-url http://localhost:9090"
echo "  python agent.py --frontend-url http://localhost:3001"
