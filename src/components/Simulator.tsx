import { useState, useEffect, useRef, useCallback } from 'react';

interface AccelData {
  x: number;
  y: number;
  z: number;
}

export default function Simulator() {
  const [buttonPressed, setButtonPressed] = useState(false);
  const [accelData, setAccelData] = useState<AccelData>({ x: 0, y: 0, z: 0 });
  const [cursorPos, setCursorPos] = useState({ x: 200, y: 150 });
  const [sensitivity, setSensitivity] = useState(5);
  const [isReading, setIsReading] = useState(false);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animFrameRef = useRef<number>(0);
  const lastTimeRef = useRef<number>(0);
  const velocityRef = useRef({ vx: 0, vy: 0 });

  const simulateAccel = useCallback(() => {
    const noise = () => (Math.random() - 0.5) * 0.5;
    return {
      x: noise() * 2,
      y: noise() * 2,
      z: 9.8 + noise() * 0.3,
    };
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const animate = (timestamp: number) => {
      const dt = lastTimeRef.current ? (timestamp - lastTimeRef.current) / 1000 : 0.016;
      lastTimeRef.current = timestamp;

      if (buttonPressed) {
        const data = simulateAccel();
        setAccelData(data);
        setIsReading(true);

        const damping = 0.95;
        velocityRef.current.vx = velocityRef.current.vx * damping + data.x * sensitivity * dt * 100;
        velocityRef.current.vy = velocityRef.current.vy * damping + data.y * sensitivity * dt * 100;

        setCursorPos(prev => {
          let newX = prev.x + velocityRef.current.vx * dt;
          let newY = prev.y + velocityRef.current.vy * dt;
          newX = Math.max(10, Math.min(canvas.width - 10, newX));
          newY = Math.max(10, Math.min(canvas.height - 10, newY));
          return { x: newX, y: newY };
        });
      } else {
        setIsReading(false);
        velocityRef.current.vx *= 0.9;
        velocityRef.current.vy *= 0.9;
        setAccelData({ x: 0, y: 0, z: 0 });
      }

      ctx.fillStyle = '#0f172a';
      ctx.fillRect(0, 0, canvas.width, canvas.height);

      ctx.strokeStyle = '#1e293b';
      ctx.lineWidth = 1;
      for (let i = 0; i < canvas.width; i += 40) {
        ctx.beginPath();
        ctx.moveTo(i, 0);
        ctx.lineTo(i, canvas.height);
        ctx.stroke();
      }
      for (let i = 0; i < canvas.height; i += 40) {
        ctx.beginPath();
        ctx.moveTo(0, i);
        ctx.lineTo(canvas.width, i);
        ctx.stroke();
      }

      ctx.fillStyle = buttonPressed ? 'rgba(59, 130, 246, 0.3)' : 'rgba(100, 116, 139, 0.1)';
      ctx.beginPath();
      ctx.arc(cursorPos.x, cursorPos.y, 20, 0, Math.PI * 2);
      ctx.fill();

      ctx.fillStyle = buttonPressed ? '#3b82f6' : '#64748b';
      ctx.strokeStyle = buttonPressed ? '#60a5fa' : '#94a3b8';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(cursorPos.x, cursorPos.y, 8, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();

      ctx.strokeStyle = buttonPressed ? '#93c5fd' : '#cbd5e1';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(cursorPos.x - 15, cursorPos.y);
      ctx.lineTo(cursorPos.x + 15, cursorPos.y);
      ctx.moveTo(cursorPos.x, cursorPos.y - 15);
      ctx.lineTo(cursorPos.x, cursorPos.y + 15);
      ctx.stroke();

      ctx.fillStyle = buttonPressed ? '#22c55e' : '#ef4444';
      ctx.beginPath();
      ctx.arc(20, 20, 6, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = '#e2e8f0';
      ctx.font = '12px monospace';
      ctx.fillText(buttonPressed ? 'ACTIVE' : 'IDLE', 32, 24);

      animFrameRef.current = requestAnimationFrame(animate);
    };

    animFrameRef.current = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(animFrameRef.current);
  }, [buttonPressed, cursorPos, sensitivity, simulateAccel]);

  return (
    <div className="bg-slate-800/50 rounded-2xl p-6 border border-slate-700">
      <h3 className="text-xl font-bold text-white mb-4 flex items-center gap-2">
        <i className="fas fa-gamepad text-blue-400"></i>
        Симулятор работы устройства
      </h3>
      
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div>
          <canvas
            ref={canvasRef}
            width={400}
            height={300}
            className="rounded-lg border border-slate-600 w-full"
          />
          
          <div className="mt-4 flex flex-wrap items-center gap-4">
            <button
              onMouseDown={() => setButtonPressed(true)}
              onMouseUp={() => setButtonPressed(false)}
              onMouseLeave={() => setButtonPressed(false)}
              onTouchStart={() => setButtonPressed(true)}
              onTouchEnd={() => setButtonPressed(false)}
              className={`px-6 py-3 rounded-lg font-bold text-white transition-all transform active:scale-95 select-none ${
                buttonPressed
                  ? 'bg-green-500 shadow-lg shadow-green-500/30 scale-95'
                  : 'bg-slate-600 hover:bg-slate-500'
              }`}
            >
              <i className="fas fa-hand-pointer mr-2"></i>
              Удерживайте кнопку
            </button>
            
            <div className="flex items-center gap-2">
              <span className="text-slate-400 text-sm">Чувствительность:</span>
              <input
                type="range"
                min="1"
                max="20"
                value={sensitivity}
                onChange={(e) => setSensitivity(Number(e.target.value))}
                className="w-24 accent-blue-500"
              />
              <span className="text-blue-400 font-mono text-sm">{sensitivity}</span>
            </div>
          </div>
        </div>

        <div className="space-y-4">
          <div className="bg-slate-900/50 rounded-lg p-4 border border-slate-700">
            <h4 className="text-sm font-semibold text-slate-300 mb-3">
              <i className="fas fa-microchip text-purple-400 mr-2"></i>
              Данные акселерометра
            </h4>
            <div className="grid grid-cols-3 gap-3">
              <div className="text-center">
                <div className="text-xs text-red-400 mb-1">X</div>
                <div className={`font-mono text-lg transition-colors ${isReading ? 'text-red-300' : 'text-slate-500'}`}>
                  {accelData.x.toFixed(2)}
                </div>
              </div>
              <div className="text-center">
                <div className="text-xs text-green-400 mb-1">Y</div>
                <div className={`font-mono text-lg transition-colors ${isReading ? 'text-green-300' : 'text-slate-500'}`}>
                  {accelData.y.toFixed(2)}
                </div>
              </div>
              <div className="text-center">
                <div className="text-xs text-blue-400 mb-1">Z</div>
                <div className={`font-mono text-lg transition-colors ${isReading ? 'text-blue-300' : 'text-slate-500'}`}>
                  {accelData.z.toFixed(2)}
                </div>
              </div>
            </div>
          </div>

          <div className="bg-slate-900/50 rounded-lg p-4 border border-slate-700">
            <h4 className="text-sm font-semibold text-slate-300 mb-3">
              <i className="fas fa-crosshairs text-cyan-400 mr-2"></i>
              Позиция курсора
            </h4>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <span className="text-xs text-slate-400">X:</span>
                <span className="ml-2 font-mono text-cyan-300">{Math.round(cursorPos.x)}</span>
              </div>
              <div>
                <span className="text-xs text-slate-400">Y:</span>
                <span className="ml-2 font-mono text-cyan-300">{Math.round(cursorPos.y)}</span>
              </div>
            </div>
          </div>

          <div className="bg-slate-900/50 rounded-lg p-4 border border-slate-700">
            <h4 className="text-sm font-semibold text-slate-300 mb-2">
              <i className="fas fa-info-circle text-yellow-400 mr-2"></i>
              Как это работает
            </h4>
            <p className="text-xs text-slate-400 leading-relaxed">
              Удерживайте кнопку — акселерометр начинает считывать ускорения. 
              Данные интегрируются (ускорение → скорость → перемещение). 
              Отпустите кнопку — считывание прекращается, курсор замирает.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
