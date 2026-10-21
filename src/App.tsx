import { useState } from 'react';
import Simulator from './components/Simulator';
import Hardware from './components/Hardware';
import FirmwareCode from './components/FirmwareCode';
import DesktopCode from './components/DesktopCode';

type Tab = 'overview' | 'hardware' | 'firmware' | 'desktop' | 'simulator';

function App() {
  const [activeTab, setActiveTab] = useState<Tab>('overview');

  const tabs: { id: Tab; label: string; icon: string; bg: string; text: string; border: string }[] = [
    { id: 'overview', label: 'Обзор', icon: 'fa-home', bg: 'rgba(59,130,246,0.2)', text: '#93c5fd', border: 'rgba(59,130,246,0.3)' },
    { id: 'hardware', label: 'Железо', icon: 'fa-tools', bg: 'rgba(249,115,22,0.2)', text: '#fdba74', border: 'rgba(249,115,22,0.3)' },
    { id: 'firmware', label: 'Прошивка', icon: 'fa-microchip', bg: 'rgba(34,197,94,0.2)', text: '#86efac', border: 'rgba(34,197,94,0.3)' },
    { id: 'desktop', label: 'Приложение', icon: 'fa-desktop', bg: 'rgba(234,179,8,0.2)', text: '#fde047', border: 'rgba(234,179,8,0.3)' },
    { id: 'simulator', label: 'Симулятор', icon: 'fa-gamepad', bg: 'rgba(168,85,247,0.2)', text: '#c4b5fd', border: 'rgba(168,85,247,0.3)' },
  ];

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-slate-800 to-slate-900 text-white">
      {/* Header */}
      <header className="border-b border-slate-700/50 bg-slate-900/80 backdrop-blur-sm sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-4 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-blue-500 to-cyan-400 flex items-center justify-center shadow-lg shadow-blue-500/20">
                <i className="fas fa-pen-fancy text-white text-lg"></i>
              </div>
              <div>
                <h1 className="text-xl font-bold bg-gradient-to-r from-blue-400 to-cyan-300 bg-clip-text text-transparent">
                  AeroPen
                </h1>
                <p className="text-xs text-slate-400">USB курсор на акселерометре</p>
              </div>
            </div>
            <div className="hidden md:flex items-center gap-2 text-xs text-slate-400">
              <span className="bg-green-500/20 text-green-300 px-2 py-1 rounded-full">
                <i className="fas fa-check-circle mr-1"></i>Windows 10
              </span>
              <span className="bg-blue-500/20 text-blue-300 px-2 py-1 rounded-full">
                <i className="fas fa-usb mr-1"></i>USB Serial
              </span>
            </div>
          </div>
        </div>
      </header>

      {/* Navigation */}
      <nav className="border-b border-slate-700/50 bg-slate-800/50">
        <div className="max-w-7xl mx-auto px-4">
          <div className="flex overflow-x-auto gap-1 py-2">
            {tabs.map((tab) => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className="flex items-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium whitespace-nowrap transition-all"
                style={activeTab === tab.id ? {
                  backgroundColor: tab.bg,
                  color: tab.text,
                  border: `1px solid ${tab.border}`,
                } : {
                  color: '#94a3b8',
                  border: '1px solid transparent',
                }}
              >
                <i className={`fas ${tab.icon}`}></i>
                {tab.label}
              </button>
            ))}
          </div>
        </div>
      </nav>

      {/* Main Content */}
      <main className="max-w-7xl mx-auto px-4 py-8">
        {activeTab === 'overview' && <OverviewTab />}
        {activeTab === 'hardware' && <Hardware />}
        {activeTab === 'firmware' && <FirmwareCode />}
        {activeTab === 'desktop' && <DesktopCode />}
        {activeTab === 'simulator' && <Simulator />}
      </main>

      {/* Footer */}
      <footer className="border-t border-slate-700/50 bg-slate-900/50 py-6">
        <div className="max-w-7xl mx-auto px-4 text-center text-xs text-slate-500">
          <p>AeroPen — проект USB-устройства управления курсором на основе акселерометра</p>
          <p className="mt-1">Arduino Pro Micro (ATmega32U4) + MPU6050 + Python Desktop App</p>
        </div>
      </footer>
    </div>
  );
}

function OverviewTab() {
  return (
    <div className="space-y-8">
      {/* Hero */}
      <div className="relative overflow-hidden rounded-2xl bg-gradient-to-br from-blue-600/20 via-slate-800 to-cyan-600/20 border border-slate-700 p-8 md:p-12">
        <div className="relative">
          <div className="flex flex-col md:flex-row items-start md:items-center gap-6">
            <div className="flex-1">
              <h2 className="text-3xl md:text-4xl font-bold mb-4">
                <span className="bg-gradient-to-r from-blue-400 via-cyan-300 to-blue-400 bg-clip-text text-transparent">
                  USB-устройство управления курсором
                </span>
              </h2>
              <p className="text-lg text-slate-300 mb-6 leading-relaxed">
                Программа для Windows 10, управляющая курсором компьютера с помощью 
                устройства в виде авторучки с акселерометром. Подключается по USB, 
                работает как с рабочей поверхностью, так и в воздухе.
              </p>
              <div className="flex flex-wrap gap-3">
                <span className="bg-blue-500/20 text-blue-300 px-3 py-1.5 rounded-full text-sm border border-blue-500/30">
                  <i className="fas fa-microchip mr-2"></i>MPU6050
                </span>
                <span className="bg-green-500/20 text-green-300 px-3 py-1.5 rounded-full text-sm border border-green-500/30">
                  <i className="fab fa-arduino mr-2"></i>Arduino Pro Micro
                </span>
                <span className="bg-yellow-500/20 text-yellow-300 px-3 py-1.5 rounded-full text-sm border border-yellow-500/30">
                  <i className="fab fa-python mr-2"></i>Python
                </span>
                <span className="bg-purple-500/20 text-purple-300 px-3 py-1.5 rounded-full text-sm border border-purple-500/30">
                  <i className="fab fa-windows mr-2"></i>Windows 10
                </span>
              </div>
            </div>
            <div className="hidden md:block">
              <div className="w-48 h-48 relative">
                <svg viewBox="0 0 200 200" className="w-full h-full">
                  <rect x="85" y="30" width="30" height="140" rx="15" fill="url(#penGrad)" stroke="#60a5fa" strokeWidth="1"/>
                  <polygon points="95,170 105,170 100,190" fill="#94a3b8"/>
                  <rect x="90" y="60" width="20" height="12" rx="3" fill="#3b82f6" stroke="#60a5fa" strokeWidth="1"/>
                  <rect x="92" y="100" width="16" height="16" rx="2" fill="#1e293b" stroke="#22c55e" strokeWidth="1"/>
                  <text x="100" y="111" textAnchor="middle" fill="#4ade80" fontSize="6">ACC</text>
                  <path d="M100,30 Q100,15 120,15 Q140,15 140,25" fill="none" stroke="#64748b" strokeWidth="2"/>
                  <rect x="135" y="20" width="15" height="10" rx="2" fill="#374151" stroke="#6b7280" strokeWidth="1"/>
                  <circle cx="100" cy="108" r="25" fill="none" stroke="#22c55e" strokeWidth="0.5" opacity="0.5"/>
                  <circle cx="100" cy="108" r="35" fill="none" stroke="#22c55e" strokeWidth="0.3" opacity="0.3"/>
                  <defs>
                    <linearGradient id="penGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                      <stop offset="0%" stopColor="#1e3a5f"/>
                      <stop offset="100%" stopColor="#0f172a"/>
                    </linearGradient>
                  </defs>
                </svg>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* How it works */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {[
          { step: '01', title: 'Устройство', desc: 'Авторучка с MPU6050 и Arduino Pro Micro. I2C на D2/D3, кнопка на D4.', icon: 'fa-pen-fancy', color: 'blue' },
          { step: '02', title: 'Подключение', desc: 'USB-кабель к компьютеру. Pro Micro определяется как COM-порт автоматически.', icon: 'fa-usb', color: 'green' },
          { step: '03', title: 'Активация', desc: 'Нажимаете кнопку — акселерометр начинает считывать ускорения.', icon: 'fa-hand-pointer', color: 'yellow' },
          { step: '04', title: 'Управление', desc: 'Перемещаете ручку — курсор следует. Отпускаете — курсор замирает.', icon: 'fa-crosshairs', color: 'purple' },
        ].map((item, i) => (
          <div key={i} className="bg-slate-800/50 rounded-xl p-5 border border-slate-700 hover:border-slate-600 transition-all group">
            <div className="flex items-center justify-between mb-3">
              <span className="text-3xl font-black opacity-20 group-hover:opacity-40 transition-opacity">
                {item.step}
              </span>
              <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${
                item.color === 'blue' ? 'bg-blue-500/20' :
                item.color === 'green' ? 'bg-green-500/20' :
                item.color === 'yellow' ? 'bg-yellow-500/20' :
                'bg-purple-500/20'
              }`}>
                <i className={`fas ${item.icon} ${
                  item.color === 'blue' ? 'text-blue-400' :
                  item.color === 'green' ? 'text-green-400' :
                  item.color === 'yellow' ? 'text-yellow-400' :
                  'text-purple-400'
                }`}></i>
              </div>
            </div>
            <h3 className="text-white font-bold mb-2">{item.title}</h3>
            <p className="text-sm text-slate-400">{item.desc}</p>
          </div>
        ))}
      </div>

      {/* Architecture */}
      <div className="bg-slate-800/50 rounded-2xl p-6 border border-slate-700">
        <h3 className="text-xl font-bold text-white mb-6 flex items-center gap-2">
          <i className="fas fa-sitemap text-cyan-400"></i>
          Архитектура системы
        </h3>

        <div className="relative">
          <svg viewBox="0 0 800 200" className="w-full" xmlns="http://www.w3.org/2000/svg">
            <rect x="20" y="40" width="180" height="120" rx="10" fill="#1e293b" stroke="#3b82f6" strokeWidth="2"/>
            <text x="110" y="65" textAnchor="middle" fill="#93c5fd" fontSize="12" fontWeight="bold">УСТРОЙСТВО</text>
            <text x="110" y="82" textAnchor="middle" fill="#64748b" fontSize="9">Arduino Pro Micro</text>
            <text x="110" y="97" textAnchor="middle" fill="#64748b" fontSize="9">MPU6050 (I2C: D2/D3)</text>
            <text x="110" y="112" textAnchor="middle" fill="#64748b" fontSize="9">Кнопка (D4)</text>
            <text x="110" y="127" textAnchor="middle" fill="#64748b" fontSize="9">LED (D6)</text>
            <text x="110" y="148" textAnchor="middle" fill="#475569" fontSize="8">ATmega32U4</text>

            <line x1="200" y1="100" x2="280" y2="100" stroke="#22c55e" strokeWidth="2" markerEnd="url(#arrowGreen)"/>
            <text x="240" y="90" textAnchor="middle" fill="#86efac" fontSize="9">USB Serial</text>
            <text x="240" y="115" textAnchor="middle" fill="#4ade80" fontSize="8">8 bytes/packet</text>

            <rect x="280" y="40" width="220" height="120" rx="10" fill="#1e293b" stroke="#eab308" strokeWidth="2"/>
            <text x="390" y="65" textAnchor="middle" fill="#fde047" fontSize="12" fontWeight="bold">КОМПЬЮТЕР</text>
            <text x="390" y="85" textAnchor="middle" fill="#64748b" fontSize="10">Python App</text>
            <text x="390" y="105" textAnchor="middle" fill="#64748b" fontSize="10">├─ Serial Read</text>
            <text x="390" y="125" textAnchor="middle" fill="#64748b" fontSize="10">├─ Integration</text>
            <text x="390" y="145" textAnchor="middle" fill="#64748b" fontSize="10">└─ Filter</text>

            <line x1="500" y1="100" x2="580" y2="100" stroke="#a855f7" strokeWidth="2" markerEnd="url(#arrowPurple)"/>
            <text x="540" y="90" textAnchor="middle" fill="#c4b5fd" fontSize="9">Win32 API</text>
            <text x="540" y="115" textAnchor="middle" fill="#a855f7" fontSize="8">SetCursorPos</text>

            <rect x="580" y="40" width="200" height="120" rx="10" fill="#1e293b" stroke="#ec4899" strokeWidth="2"/>
            <text x="680" y="65" textAnchor="middle" fill="#f9a8d4" fontSize="12" fontWeight="bold">ЭКРАН</text>
            <text x="680" y="90" textAnchor="middle" fill="#64748b" fontSize="10">Курсор перемещается</text>
            <text x="680" y="110" textAnchor="middle" fill="#64748b" fontSize="10">по координатам</text>
            <text x="680" y="130" textAnchor="middle" fill="#64748b" fontSize="10">(x, y)</text>
            <text x="680" y="150" textAnchor="middle" fill="#64748b" fontSize="10">1920 × 1080</text>

            <defs>
              <marker id="arrowGreen" markerWidth="10" markerHeight="7" refX="10" refY="3.5" orient="auto">
                <polygon points="0 0, 10 3.5, 0 7" fill="#22c55e"/>
              </marker>
              <marker id="arrowPurple" markerWidth="10" markerHeight="7" refX="10" refY="3.5" orient="auto">
                <polygon points="0 0, 10 3.5, 0 7" fill="#a855f7"/>
              </marker>
            </defs>
          </svg>
        </div>
      </div>

      {/* Key features */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-slate-800/50 rounded-xl p-5 border border-slate-700">
          <div className="w-12 h-12 rounded-xl bg-green-500/20 flex items-center justify-center mb-4">
            <i className="fas fa-hand-paper text-green-400 text-xl"></i>
          </div>
          <h4 className="text-white font-bold mb-2">Кнопка-активатор</h4>
          <p className="text-sm text-slate-400">
            Курсор двигается только при удержании кнопки. Отпустил — курсор застыл. 
            Нет случайных перемещений.
          </p>
        </div>
        <div className="bg-slate-800/50 rounded-xl p-5 border border-slate-700">
          <div className="w-12 h-12 rounded-xl bg-blue-500/20 flex items-center justify-center mb-4">
            <i className="fas fa-filter text-blue-400 text-xl"></i>
          </div>
          <h4 className="text-white font-bold mb-2">Фильтрация сигнала</h4>
          <p className="text-sm text-slate-400">
            Калибровка нуля, мёртвая зона, скользящее среднее и затухание — 
            для точного и плавного управления.
          </p>
        </div>
        <div className="bg-slate-800/50 rounded-xl p-5 border border-slate-700">
          <div className="w-12 h-12 rounded-xl bg-purple-500/20 flex items-center justify-center mb-4">
            <i className="fas fa-expand text-purple-400 text-xl"></i>
          </div>
          <h4 className="text-white font-bold mb-2">Два режима работы</h4>
          <p className="text-sm text-slate-400">
            С рабочей поверхностью (стол) или в воздухе. 
            Переключение в настройках чувствительности.
          </p>
        </div>
      </div>

      {/* Technical details */}
      <div className="bg-slate-800/50 rounded-2xl p-6 border border-slate-700">
        <h3 className="text-xl font-bold text-white mb-4 flex items-center gap-2">
          <i className="fas fa-calculator text-pink-400"></i>
          Математика: от ускорения к позиции
        </h3>
        
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          <div className="bg-slate-900/50 rounded-lg p-4 border border-slate-700">
            <h4 className="text-sm font-semibold text-slate-300 mb-3">Формулы</h4>
            <div className="space-y-3 font-mono text-sm">
              <div className="flex items-center gap-3">
                <span className="text-pink-400 w-8">v(t)</span>
                <span className="text-slate-400">=</span>
                <span className="text-slate-300">v(t-1) × damping + a(t) × S × dt</span>
              </div>
              <div className="flex items-center gap-3">
                <span className="text-cyan-400 w-8">p(t)</span>
                <span className="text-slate-400">=</span>
                <span className="text-slate-300">p(t-1) + v(t) × dt</span>
              </div>
              <div className="flex items-center gap-3">
                <span className="text-yellow-400 w-8">a'(t)</span>
                <span className="text-slate-400">=</span>
                <span className="text-slate-300">a(t) - offset, если |a'| {'>'} deadzone</span>
              </div>
            </div>
          </div>
          <div className="bg-slate-900/50 rounded-lg p-4 border border-slate-700">
            <h4 className="text-sm font-semibold text-slate-300 mb-3">Параметры</h4>
            <div className="space-y-2 text-sm">
              <div className="flex justify-between">
                <span className="text-slate-400">Частота опроса:</span>
                <span className="text-green-300 font-mono">200 Гц</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-400">Затухание (damping):</span>
                <span className="text-green-300 font-mono">0.92</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-400">Чувствительность (S):</span>
                <span className="text-green-300 font-mono">5.0</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-400">Мёртвая зона:</span>
                <span className="text-green-300 font-mono">0.05g</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-400">Размер буфера фильтра:</span>
                <span className="text-green-300 font-mono">5 отсчётов</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

export default App;
