#!/bin/bash
echo "Iniciando túnel Bore..."
./bore local 25565 --to bore.pub &
sleep 2
echo "Iniciando servidor Minecraft..."
./start.sh &
echo "Ambos iniciados em background."