export default function Hardware() {
  return (
    <div className="bg-slate-800/50 rounded-2xl p-6 border border-slate-700">
      <h3 className="text-xl font-bold text-white mb-4 flex items-center gap-2">
        <i className="fas fa-tools text-orange-400"></i>
        Аппаратная часть
      </h3>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="space-y-4">
          <h4 className="text-sm font-semibold text-slate-300 flex items-center gap-2">
            <i className="fas fa-shopping-cart text-green-400"></i>
            Необходимые компоненты
          </h4>
          
          <div className="space-y-2">
            {[
              { name: 'Arduino Pro Micro (ATmega32U4)', desc: 'Микроконтроллер с нативным USB', price: '~300₽', icon: 'fa-microchip' },
              { name: 'MPU6050 (GY-521)', desc: 'Акселерометр + гироскоп (I2C)', price: '~150₽', icon: 'fa-square' },
              { name: 'Тактовая кнопка 6×6мм', desc: 'Кнопка на корпусе устройства', price: '~10₽', icon: 'fa-circle-dot' },
              { name: 'Корпус авторучки', desc: 'Или напечатать на 3D-принтере', price: '~50₽', icon: 'fa-pen' },
              { name: 'USB кабель micro-USB', desc: 'Подключение к компьютеру', price: '~100₽', icon: 'fa-usb' },
              { name: 'Провода, припой', desc: 'Для монтажа', price: '~100₽', icon: 'fa-bolt' },
            ].map((item, i) => (
              <div key={i} className="flex items-center gap-3 bg-slate-900/50 rounded-lg p-3 border border-slate-700/50">
                <div className="w-8 h-8 rounded-full bg-slate-700 flex items-center justify-center">
                  <i className={`fas ${item.icon} text-xs text-orange-300`}></i>
                </div>
                <div className="flex-1 min-w-0">
                  <div className="text-sm text-white font-medium truncate">{item.name}</div>
                  <div className="text-xs text-slate-400">{item.desc}</div>
                </div>
                <div className="text-xs text-green-400 font-mono">{item.price}</div>
              </div>
            ))}
          </div>

          <div className="bg-slate-900/50 rounded-lg p-3 border border-slate-700">
            <div className="flex justify-between items-center">
              <span className="text-sm text-slate-300">Общая стоимость:</span>
              <span className="text-lg font-bold text-green-400">~710₽</span>
            </div>
          </div>
        </div>

        <div className="space-y-4">
          <h4 className="text-sm font-semibold text-slate-300 flex items-center gap-2">
            <i className="fas fa-project-diagram text-cyan-400"></i>
            Схема подключения (Arduino Pro Micro)
          </h4>

          <div className="bg-slate-900 rounded-lg p-4 border border-slate-700">
            <svg viewBox="0 0 420 340" className="w-full" xmlns="http://www.w3.org/2000/svg">
              <rect x="150" y="20" width="110" height="170" rx="8" fill="#1e3a5f" stroke="#3b82f6" strokeWidth="2"/>
              <text x="205" y="42" textAnchor="middle" fill="#93c5fd" fontSize="10" fontWeight="bold">Arduino</text>
              <text x="205" y="55" textAnchor="middle" fill="#93c5fd" fontSize="9">Pro Micro</text>
              <text x="205" y="68" textAnchor="middle" fill="#60a5fa" fontSize="7">(ATmega32U4)</text>
              
              <rect x="185" y="10" width="40" height="15" rx="3" fill="#374151" stroke="#6b7280" strokeWidth="1"/>
              <text x="205" y="21" textAnchor="middle" fill="#9ca3af" fontSize="7">USB</text>

              <text x="155" y="90" fill="#f87171" fontSize="7">TX/D1</text>
              <text x="155" y="103" fill="#f87171" fontSize="7">RX/D0</text>
              <text x="155" y="116" fill="#fbbf24" fontSize="7">GND</text>
              <text x="155" y="129" fill="#fbbf24" fontSize="7">GND</text>
              <text x="155" y="142" fill="#a78bfa" fontSize="7">D4</text>
              <text x="155" y="155" fill="#a78bfa" fontSize="7">D6/A7</text>
              <text x="155" y="168" fill="#a78bfa" fontSize="7">D8/A8</text>
              <text x="155" y="181" fill="#a78bfa" fontSize="7">D9/A9</text>

              <text x="260" y="90" fill="#22c55e" fontSize="7">D2/SDA</text>
              <text x="260" y="103" fill="#22c55e" fontSize="7">D3/SCL</text>
              <text x="260" y="116" fill="#f87171" fontSize="7">VCC/5V</text>
              <text x="260" y="129" fill="#f87171" fontSize="7">RAW</text>
              <text x="260" y="142" fill="#fbbf24" fontSize="7">GND</text>
              <text x="260" y="155" fill="#a78bfa" fontSize="7">D5</text>
              <text x="260" y="168" fill="#a78bfa" fontSize="7">D7</text>
              <text x="260" y="181" fill="#a78bfa" fontSize="7">D10</text>

              <rect x="320" y="60" width="85" height="110" rx="6" fill="#1a2e1a" stroke="#22c55e" strokeWidth="2"/>
              <text x="362" y="82" textAnchor="middle" fill="#86efac" fontSize="10" fontWeight="bold">MPU6050</text>
              <text x="362" y="96" textAnchor="middle" fill="#4ade80" fontSize="8">GY-521</text>
              
              <text x="325" y="115" fill="#ef4444" fontSize="7">VCC</text>
              <text x="325" y="128" fill="#eab308" fontSize="7">GND</text>
              <text x="325" y="141" fill="#3b82f6" fontSize="7">SDA</text>
              <text x="325" y="154" fill="#3b82f6" fontSize="7">SCL</text>

              <line x1="260" y1="113" x2="320" y2="112" stroke="#ef4444" strokeWidth="1.5"/>
              <text x="290" y="108" fill="#fca5a5" fontSize="6">VCC</text>
              
              <line x1="260" y1="139" x2="320" y2="125" stroke="#eab308" strokeWidth="1.5"/>
              <text x="288" y="136" fill="#fde047" fontSize="6">GND</text>
              
              <line x1="260" y1="87" x2="320" y2="138" stroke="#3b82f6" strokeWidth="1.5"/>
              <text x="280" y="100" fill="#93c5fd" fontSize="6" transform="rotate(25, 280, 100)">SDA→D2</text>
              
              <line x1="260" y1="100" x2="320" y2="151" stroke="#06b6d4" strokeWidth="1.5"/>
              <text x="280" y="132" fill="#67e8f9" fontSize="6" transform="rotate(25, 280, 132)">SCL→D3</text>

              <circle cx="75" cy="230" r="25" fill="#374151" stroke="#f59e0b" strokeWidth="2"/>
              <circle cx="75" cy="230" r="15" fill="#4b5563" stroke="#fbbf24" strokeWidth="1.5"/>
              <text x="75" y="234" textAnchor="middle" fill="#fde047" fontSize="8" fontWeight="bold">BTN</text>
              <text x="75" y="270" textAnchor="middle" fill="#9ca3af" fontSize="8">Кнопка</text>

              <line x1="100" y1="225" x2="155" y2="139" stroke="#a78bfa" strokeWidth="1.5"/>
              <text x="120" y="175" fill="#c4b5fd" fontSize="6" transform="rotate(-55, 120, 175)">D4</text>
              
              <line x1="95" y1="250" x2="155" y2="113" stroke="#eab308" strokeWidth="1.5"/>
              <text x="115" y="170" fill="#fde047" fontSize="6" transform="rotate(-58, 115, 170)">GND</text>

              <circle cx="75" cy="120" r="10" fill="#22c55e" opacity="0.7"/>
              <circle cx="75" cy="120" r="6" fill="#4ade80"/>
              <text x="75" y="140" textAnchor="middle" fill="#86efac" fontSize="7">LED</text>
              <line x1="85" y1="120" x2="155" y2="152" stroke="#22c55e" strokeWidth="1.5"/>
              <text x="115" y="132" fill="#86efac" fontSize="6">D6</text>

              <text x="210" y="310" textAnchor="middle" fill="#64748b" fontSize="9">
                Схема подключения AeroPen (Arduino Pro Micro)
              </text>
              <text x="210" y="325" textAnchor="middle" fill="#475569" fontSize="8">
                I2C: SDA=D2, SCL=D3 | Кнопка: D4 | LED: D6
              </text>
            </svg>
          </div>

          <div className="bg-slate-900/50 rounded-lg p-3 border border-slate-700">
            <h5 className="text-xs font-semibold text-slate-400 mb-2">Таблица соединений</h5>
            <table className="w-full text-xs">
              <thead>
                <tr className="text-slate-500">
                  <th className="text-left pb-1">Компонент</th>
                  <th className="text-left pb-1">Пин</th>
                  <th className="text-left pb-1">Arduino Pro Micro</th>
                </tr>
              </thead>
              <tbody className="text-slate-300">
                <tr className="border-t border-slate-700/50"><td className="py-1">MPU6050 VCC</td><td>→</td><td className="text-red-300">VCC (5V)</td></tr>
                <tr className="border-t border-slate-700/50"><td className="py-1">MPU6050 GND</td><td>→</td><td className="text-yellow-300">GND</td></tr>
                <tr className="border-t border-slate-700/50"><td className="py-1">MPU6050 SDA</td><td>→</td><td className="text-green-300">D2 (SDA)</td></tr>
                <tr className="border-t border-slate-700/50"><td className="py-1">MPU6050 SCL</td><td>→</td><td className="text-green-300">D3 (SCL)</td></tr>
                <tr className="border-t border-slate-700/50"><td className="py-1">Кнопка (pin 1)</td><td>→</td><td className="text-purple-300">D4</td></tr>
                <tr className="border-t border-slate-700/50"><td className="py-1">Кнопка (pin 2)</td><td>→</td><td className="text-yellow-300">GND</td></tr>
                <tr className="border-t border-slate-700/50"><td className="py-1">LED (+)</td><td>→</td><td className="text-green-300">D6</td></tr>
                <tr className="border-t border-slate-700/50"><td className="py-1">LED (−)</td><td>→</td><td className="text-yellow-300">GND (через 220Ω)</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <div className="mt-6 grid grid-cols-1 md:grid-cols-2 gap-4">
        <div className="bg-gradient-to-br from-blue-500/10 to-cyan-500/10 rounded-lg p-4 border border-blue-500/30">
          <h4 className="text-sm font-bold text-blue-300 mb-2">
            <i className="fas fa-pencil-alt mr-2"></i>
            Вариант 1: С рабочей поверхностью
          </h4>
          <p className="text-xs text-blue-200/70 leading-relaxed">
            Устройство работает как обычная ручка на столе. Акселерометр считывает 
            горизонтальные перемещения. Кнопка фиксирует/отпускает курсор.
          </p>
        </div>
        <div className="bg-gradient-to-br from-purple-500/10 to-pink-500/10 rounded-lg p-4 border border-purple-500/30">
          <h4 className="text-sm font-bold text-purple-300 mb-2">
            <i className="fas fa-wind mr-2"></i>
            Вариант 2: В воздухе (без поверхности)
          </h4>
          <p className="text-xs text-purple-200/70 leading-relaxed">
            Устройство используется как «воздушная мышь». Требует более сложной 
            обработки: компенсация гравитации, фильтрация дрожания рук.
          </p>
        </div>
      </div>

      <div className="mt-4 bg-amber-500/10 border border-amber-500/30 rounded-lg p-4">
        <h4 className="text-sm font-semibold text-amber-300 mb-2">
          <i className="fas fa-exclamation-triangle mr-2"></i>
          Важное замечание по пинам
        </h4>
        <p className="text-xs text-amber-200/80 leading-relaxed">
          Arduino Pro Micro на ATmega32U4 <strong>не имеет выводов A4/A5</strong> как у Arduino Uno. 
          Аппаратные пины I2C на Pro Micro расположены на <strong>D2 (SDA)</strong> и <strong>D3 (SCL)</strong>. 
          Библиотека Wire автоматически использует эти пины при вызове <code className="bg-slate-800 px-1 rounded">Wire.begin()</code>.
        </p>
      </div>
    </div>
  );
}
