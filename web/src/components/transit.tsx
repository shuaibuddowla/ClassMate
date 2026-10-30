"use client";

import { useEffect, useState, useRef, useMemo } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import "./transit.css";
import {
  Bus,
  Search,
  Navigation,
  Phone,
  Bell,
  Layers,
  MapPin,
  Clock,
  Compass,
  Radio,
  CheckCircle2,
  ChevronRight,
  UserCheck,
  ShieldAlert,
  Crosshair,
  Wifi,
  WifiOff
} from "lucide-react";

export interface MbstuBusData {
  id: string;
  nameBn: string;
  nameEn: string;
  lat: number;
  lon: number;
  speed: number;
  heading: number;
  routeName: string;
  driverName: string;
  driverPhone: string;
  capacity: string;
  isDriving: boolean;
  color: string;
  lastUpdated?: string;
}

export interface Station {
  id: string;
  name: string;
  lat: number;
  lon: number;
}

export const STATIONS: Station[] = [
  { id: "mbstu", name: "MBSTU Campus Stand", lat: 24.2349, lon: 89.8916 },
  { id: "gate", name: "Santosh Gate / Bazaar", lat: 24.2378, lon: 89.8942 },
  { id: "baby_stand", name: "Baby Stand", lat: 24.2405, lon: 89.9002 },
  { id: "old_stand", name: "Old Bus Stand", lat: 24.2440, lon: 89.9070 },
  { id: "nirala", name: "Nirala (Tangail City Center)", lat: 24.2498, lon: 89.9165 }
];

const ROUTE_COORDS: [number, number][] = [
  [24.2349, 89.8916], // MBSTU Stand
  [24.2365, 89.8925], // Campus Mosque
  [24.2378, 89.8942], // Santosh Gate / Bazaar
  [24.2405, 89.9002], // Baby Stand
  [24.2440, 89.9070], // Old Bus Stand
  [24.2475, 89.9125], // Victoria Road
  [24.2498, 89.9165]  // Nirala
];

// Initial real MBSTU fleet definitions - all defaulted to 0 speed (parked at campus)
const INITIAL_FLEET: MbstuBusData[] = [
  { id: "SB0001", nameBn: "গীতাঞ্জলি", nameEn: "Gitanjali", lat: 24.234833, lon: 89.892358, speed: 0, heading: 171, routeName: "MBSTU Campus Standby", driverName: "MD. Mizanur Rahman", driverPhone: "01711234567", capacity: "42 Seater", isDriving: false, color: "#6366f1" },
  { id: "SB0002", nameBn: "অগ্নিবীণা", nameEn: "Agnibeena", lat: 24.235100, lon: 89.891400, speed: 0, heading: 180, routeName: "Campus ➔ Nirala", driverName: "MD. Rafiqul Islam", driverPhone: "01712345678", capacity: "42 Seater", isDriving: false, color: "#0284c7" },
  { id: "SB0003", nameBn: "সোনারতরী", nameEn: "Sonartori", lat: 24.235010, lon: 89.891573, speed: 0, heading: 181, routeName: "Campus ➔ Tangail City", driverName: "MD. Kamal Hossain", driverPhone: "01713456789", capacity: "42 Seater", isDriving: false, color: "#0d9488" },
  { id: "SB0004", nameBn: "ধলেশ্বরী", nameEn: "Dholeshwari", lat: 24.235222, lon: 89.890800, speed: 0, heading: 101, routeName: "MBSTU Standby", driverName: "MD. Abdul Alim", driverPhone: "01714567890", capacity: "42 Seater", isDriving: false, color: "#d97706" },
  { id: "SB0005", nameBn: "যমুনা", nameEn: "Jamuna", lat: 24.235100, lon: 89.891200, speed: 0, heading: 180, routeName: "Campus ➔ New Bus Stand", driverName: "MD. Jahangir Alam", driverPhone: "01715678901", capacity: "42 Seater", isDriving: false, color: "#ea580c" },
  { id: "SB0006", nameBn: "মেঘনা", nameEn: "Meghna", lat: 24.234900, lon: 89.891900, speed: 0, heading: 0, routeName: "MBSTU Standby", driverName: "MD. Sirajul Islam", driverPhone: "01716789012", capacity: "42 Seater", isDriving: false, color: "#2563eb" },
  { id: "SB0007", nameBn: "সুরমা", nameEn: "Surma", lat: 24.235300, lon: 89.892100, speed: 0, heading: 90, routeName: "Campus ➔ Railway Station", driverName: "MD. Nazrul Islam", driverPhone: "01717890123", capacity: "42 Seater", isDriving: false, color: "#7c3aed" },
  { id: "SB0008", nameBn: "কর্ণফুলী", nameEn: "Karnafuli", lat: 24.235400, lon: 89.891500, speed: 0, heading: 180, routeName: "MBSTU Standby", driverName: "MD. Faruk Ahmed", driverPhone: "01718901234", capacity: "42 Seater", isDriving: false, color: "#db2777" },
  { id: "SB0009", nameBn: "বনলতা", nameEn: "Banalata", lat: 24.235258, lon: 89.890181, speed: 0, heading: 0, routeName: "Campus ➔ Nirala", driverName: "MD. Habibur Rahman", driverPhone: "01719012345", capacity: "42 Seater", isDriving: false, color: "#059669" },
  { id: "SB0010", nameBn: "রক্তকরবী", nameEn: "Raktakarabi", lat: 24.235150, lon: 89.891650, speed: 0, heading: 0, routeName: "MBSTU Standby", driverName: "MD. Anisur Rahman", driverPhone: "01720123456", capacity: "42 Seater", isDriving: false, color: "#e11d48" }
];

function calculateDistanceMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371e3;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

export function Transit({ ctx }: { ctx?: any }) {
  const [fleet, setFleet] = useState<MbstuBusData[]>(INITIAL_FLEET);
  const [selectedBusId, setSelectedBusId] = useState<string>("SB0003");
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedStationId, setSelectedStationId] = useState<string>("gate");
  const [userLocation, setUserLocation] = useState<{ lat: number; lon: number } | null>(null);
  const [isLiveWs, setIsLiveWs] = useState(false);
  const [satelliteView, setSatelliteView] = useState(true);
  const [alertEnabled, setAlertEnabled] = useState(true);
  const [lastPacketTime, setLastPacketTime] = useState<string>("Connecting...");

  const mapContainerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const markersRef = useRef<{ [busId: string]: L.Marker }>({});
  const stationMarkersRef = useRef<L.Marker[]>([]);
  const userMarkerRef = useRef<L.Marker | null>(null);
  const satelliteLayerRef = useRef<L.TileLayer | null>(null);
  const streetLayerRef = useRef<L.TileLayer | null>(null);
  const routePolylineRef = useRef<L.Polyline | null>(null);

  const selectedBus = fleet.find((b) => b.id === selectedBusId) || fleet[0];

  const targetStation =
    selectedStationId === "user_gps" && userLocation
      ? { id: "user_gps", name: "Your Current Location (GPS)", lat: userLocation.lat, lon: userLocation.lon }
      : STATIONS.find((s) => s.id === selectedStationId) || STATIONS[1];

  const distanceToStationMeters = calculateDistanceMeters(
    selectedBus.lat,
    selectedBus.lon,
    targetStation.lat,
    targetStation.lon
  );

  // ETA Calculation: if parked at campus with 0 speed, indicate parked
  const isParked = selectedBus.speed === 0;
  const effectiveSpeedKmH = Math.max(selectedBus.speed, 25);
  const etaMinutes = isParked ? 0 : Math.max(1, Math.round((distanceToStationMeters / 1000 / effectiveSpeedKmH) * 60));

  // Initialize Map with Google Maps Satellite Hybrid
  useEffect(() => {
    if (!mapContainerRef.current || mapRef.current) return;

    // Centered at MBSTU Campus
    const map = L.map(mapContainerRef.current, {
      center: [24.2355, 89.8916],
      zoom: 16,
      zoomControl: false,
      attributionControl: true
    });

    // Official Google Maps Satellite Hybrid (lyrs=y: satellite + roads + labels)
    const googleSatLayer = L.tileLayer(
      "https://mt{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}",
      {
        subdomains: ["0", "1", "2", "3"],
        maxZoom: 21,
        attribution: "&copy; Google Maps"
      }
    );

    // Official Google Maps Roadmap (lyrs=m: standard street map)
    const googleStreetLayer = L.tileLayer(
      "https://mt{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}",
      {
        subdomains: ["0", "1", "2", "3"],
        maxZoom: 21,
        attribution: "&copy; Google Maps"
      }
    );

    satelliteLayerRef.current = googleSatLayer;
    streetLayerRef.current = googleStreetLayer;

    if (satelliteView) {
      googleSatLayer.addTo(map);
    } else {
      googleStreetLayer.addTo(map);
    }

    L.control.zoom({ position: "bottomright" }).addTo(map);

    // MBSTU Campus Boundary Geofence Circle
    L.circle([24.2349, 89.8916], {
      radius: 280,
      color: "#38bdf8",
      weight: 2,
      fillColor: "#0284c7",
      fillOpacity: 0.15,
      dashArray: "5, 5"
    }).addTo(map);

    // Route Polyline
    routePolylineRef.current = L.polyline(ROUTE_COORDS, {
      color: "#38bdf8",
      weight: 4,
      opacity: 0.8,
      dashArray: "6, 8"
    }).addTo(map);

    // Stoppages Markers
    STATIONS.forEach((st) => {
      const icon = L.divIcon({
        className: "station-icon",
        html: `
          <div style="background:#0f172a;border:2.5px solid #38bdf8;border-radius:50%;width:14px;height:14px;box-shadow:0 0 10px rgba(56,189,248,0.9);"></div>
        `,
        iconSize: [14, 14],
        iconAnchor: [7, 7]
      });
      const stMarker = L.marker([st.lat, st.lon], { icon }).addTo(map);
      stMarker.bindTooltip(st.name, {
        permanent: false,
        direction: "top",
        className: "station-tooltip"
      });
      stationMarkersRef.current.push(stMarker);
    });

    mapRef.current = map;

    // Trigger size invalidation to guarantee full container rendering
    setTimeout(() => {
      map.invalidateSize();
    }, 200);

    const resizeObserver = new ResizeObserver(() => {
      map.invalidateSize();
    });
    if (mapContainerRef.current) {
      resizeObserver.observe(mapContainerRef.current);
    }

    // User GPS tracking
    if ("geolocation" in navigator) {
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          const uLat = pos.coords.latitude;
          const uLon = pos.coords.longitude;
          setUserLocation({ lat: uLat, lon: uLon });

          const userIcon = L.divIcon({
            className: "user-gps-container",
            html: `
              <div class="user-gps-marker">
                <div class="user-gps-radar"></div>
              </div>
            `,
            iconSize: [20, 20],
            iconAnchor: [10, 10]
          });

          if (!userMarkerRef.current) {
            userMarkerRef.current = L.marker([uLat, uLon], { icon: userIcon }).addTo(map);
            userMarkerRef.current.bindTooltip("You are here", { permanent: false, direction: "top" });
          } else {
            userMarkerRef.current.setLatLng([uLat, uLon]);
          }
        },
        () => {},
        { enableHighAccuracy: true, timeout: 6000 }
      );
    }

    return () => {
      resizeObserver.disconnect();
      map.remove();
      mapRef.current = null;
    };
  }, []);

  // Toggle Map Tiles (Satellite Hybrid vs Google Maps Street)
  useEffect(() => {
    if (!mapRef.current || !satelliteLayerRef.current || !streetLayerRef.current) return;
    if (satelliteView) {
      if (mapRef.current.hasLayer(streetLayerRef.current)) {
        mapRef.current.removeLayer(streetLayerRef.current);
      }
      satelliteLayerRef.current.addTo(mapRef.current);
    } else {
      if (mapRef.current.hasLayer(satelliteLayerRef.current)) {
        mapRef.current.removeLayer(satelliteLayerRef.current);
      }
      streetLayerRef.current.addTo(mapRef.current);
    }
  }, [satelliteView]);

  // Update Fleet Markers on Map
  useEffect(() => {
    if (!mapRef.current) return;
    const map = mapRef.current;

    fleet.forEach((bus) => {
      const isSelected = bus.id === selectedBusId;
      const markerColor = bus.color || (isSelected ? "#0284c7" : "#475569");

      const busIconHtml = `
        <div class="custom-bus-marker">
          <div class="custom-bus-puck ${bus.speed > 0 ? "moving" : ""}" style="background:${markerColor};border-color:${isSelected ? "#ffffff" : "#cbd5e1"};transform:scale(${isSelected ? 1.15 : 1});">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M8 6v6"></path>
              <path d="M15 6v6"></path>
              <path d="M2 12h19.6"></path>
              <path d="M18 18h3s.5-1.7.8-2.8c.1-.4.2-.8.2-1.2 0-.4-.1-.8-.2-1.2l-1.4-5C20.1 6.8 19.1 6 18 6H4C2.9 6 1.9 6.8 1.6 7.8L.2 12.8c-.1.4-.2.8-.2 1.2 0 .4.1.8.2 1.2.3 1.1.8 2.8.8 2.8h3"></path>
              <circle cx="7" cy="18" r="2"></circle>
              <circle cx="17" cy="18" r="2"></circle>
            </svg>
            <div class="custom-bus-heading" style="transform: rotate(${bus.heading}deg) translate(0, -18px);"></div>
          </div>
          <div class="custom-bus-label">
            ${bus.nameBn} ${bus.speed > 0 ? `· ${bus.speed} km/h` : "· Parked"}
          </div>
        </div>
      `;

      const busIcon = L.divIcon({
        className: "bus-custom-div",
        html: busIconHtml,
        iconSize: [44, 44],
        iconAnchor: [22, 22]
      });

      if (markersRef.current[bus.id]) {
        markersRef.current[bus.id].setLatLng([bus.lat, bus.lon]);
        markersRef.current[bus.id].setIcon(busIcon);
      } else {
        const marker = L.marker([bus.lat, bus.lon], { icon: busIcon }).addTo(map);
        marker.on("click", () => {
          setSelectedBusId(bus.id);
        });
        markersRef.current[bus.id] = marker;
      }
    });
  }, [fleet, selectedBusId]);

  // Center on Selected Bus
  const centerOnBus = (bus: MbstuBusData) => {
    if (!mapRef.current) return;
    mapRef.current.flyTo([bus.lat, bus.lon], 17, { animate: true, duration: 1.2 });
  };

  const centerOnUser = () => {
    if (!mapRef.current || !userLocation) return;
    mapRef.current.flyTo([userLocation.lat, userLocation.lon], 16, { animate: true, duration: 1.2 });
  };

  const centerCampus = () => {
    if (!mapRef.current) return;
    mapRef.current.flyTo([24.2355, 89.8916], 16, { animate: true, duration: 1.2 });
  };

  // REAL WebSocket Connection with Genuine Socket.IO Handshake
  useEffect(() => {
    let ws: WebSocket | null = null;
    let heartbeatTimer: NodeJS.Timeout | null = null;
    let isDisposed = false;

    const connectWs = () => {
      try {
        ws = new WebSocket("wss://transport.mbstu.ac.bd/socket.io/?EIO=4&transport=websocket");

        ws.onopen = () => {
          // Socket opened, waiting for Engine.IO 0 packet
        };

        ws.onmessage = (event) => {
          if (isDisposed) return;
          const raw = String(event.data);

          // 1. Engine.IO Handshake -> Send Socket.IO 40 connect packet
          if (raw.startsWith("0")) {
            ws?.send("40");
            setIsLiveWs(true);

            // Engine.IO Heartbeat every 20s
            heartbeatTimer = setInterval(() => {
              if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send("2"); // ping
              }
            }, 20000);
          }
          // 2. Pong response
          else if (raw === "3") {
            // pong received
          }
          // 3. Socket.IO Connected
          else if (raw.startsWith("40")) {
            setIsLiveWs(true);
          }
          // 4. Telemetry Packets
          else if (raw.startsWith("42")) {
            try {
              const parsed = JSON.parse(raw.slice(2));
              if (!Array.isArray(parsed) || parsed.length < 2) return;
              const evt = parsed[0];
              const payload = parsed[1];

              if (evt === "flutter-bus-data" || evt === "studentGpsData") {
                const busId = payload.id || payload.busId;
                const busName = payload.busName;
                const lat = Number(payload.lat);
                const lon = Number(payload.lon || payload.long);
                const speed = Number(payload.speed ?? 0);
                const heading = Number(payload.heading ?? 0);
                const isDriving = payload.isDriving ?? (speed > 0);
                const time = payload.time || new Date().toLocaleTimeString();

                if (!busId || isNaN(lat) || isNaN(lon)) return;

                setLastPacketTime(time);

                setFleet((prevFleet) => {
                  const existingIdx = prevFleet.findIndex((b) => b.id === busId || b.nameBn === busName);
                  if (existingIdx >= 0) {
                    const updated = [...prevFleet];
                    updated[existingIdx] = {
                      ...updated[existingIdx],
                      lat,
                      lon,
                      speed,
                      heading,
                      isDriving,
                      lastUpdated: time
                    };
                    return updated;
                  } else {
                    // New bus detected dynamically from live stream
                    const newBus: MbstuBusData = {
                      id: busId,
                      nameBn: busName || busId,
                      nameEn: busId,
                      lat,
                      lon,
                      speed,
                      heading,
                      routeName: speed > 0 ? "In Transit" : "MBSTU Campus Standby",
                      driverName: "Assigned Driver",
                      driverPhone: "01700000000",
                      capacity: "42 Seater",
                      isDriving,
                      color: "#0284c7",
                      lastUpdated: time
                    };
                    return [...prevFleet, newBus];
                  }
                });
              }
            } catch (err) {
              // Ignore unparseable packet
            }
          }
        };

        ws.onclose = () => {
          if (!isDisposed) {
            setIsLiveWs(false);
            // Reconnect after 4s
            setTimeout(connectWs, 4000);
          }
        };

        ws.onerror = () => {
          if (!isDisposed) {
            setIsLiveWs(false);
          }
        };
      } catch (e) {
        setIsLiveWs(false);
      }
    };

    connectWs();

    return () => {
      isDisposed = true;
      if (heartbeatTimer) clearInterval(heartbeatTimer);
      if (ws) ws.close();
    };
  }, []);

  const filteredFleet = fleet.filter(
    (b) =>
      b.nameBn.toLowerCase().includes(searchQuery.toLowerCase()) ||
      b.nameEn.toLowerCase().includes(searchQuery.toLowerCase()) ||
      b.id.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div className="transit-container">
      {/* Fullscreen Leaflet Map Canvas */}
      <div className="transit-map-wrapper">
        <div ref={mapContainerRef} className="transit-map-canvas" />
      </div>

      {/* Floating Top Controls (Google Maps Style) */}
      <div className="transit-top-bar">
        {/* Search Bus */}
        <div className="transit-search-box">
          <Search size={18} color="#94a3b8" />
          <input
            type="text"
            placeholder="Search bus (অগ্নিবীণা, সোনারতরী, SB0003)..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="transit-search-input"
          />
        </div>

        {/* Map Actions */}
        <div className="transit-map-actions">
          {/* Live Telemetry Status */}
          <div className={`transit-live-badge ${isLiveWs ? "online" : "offline"}`}>
            <span className="transit-pulse-dot" style={{ background: isLiveWs ? "#34d399" : "#fbbf24" }} />
            {isLiveWs ? "MBSTU LIVE" : "CONNECTING"}
          </div>

          {/* Google Satellite Toggle */}
          <button
            onClick={() => setSatelliteView(!satelliteView)}
            className="transit-action-btn"
            title="Toggle Google Satellite / Street"
          >
            <Layers size={16} />
            <span>{satelliteView ? "Google Satellite" : "Street"}</span>
          </button>

          {/* GPS Location */}
          <button
            onClick={centerOnUser}
            className="transit-action-btn"
            title="Find My Location"
          >
            <Navigation size={16} />
            <span>GPS</span>
          </button>

          {/* Campus Reset */}
          <button
            onClick={centerCampus}
            className="transit-action-btn"
            title="Center on MBSTU Campus"
          >
            <MapPin size={16} />
            <span>Campus</span>
          </button>
        </div>
      </div>

      {/* Floating Transit Panel (Google Maps Transit Card) */}
      <div className="transit-panel">
        <div className="transit-panel-scroll">

          {/* Active Bus Card */}
          <div className="transit-card">
            <div className="transit-hero">
              <div>
                <div className="transit-hero-title">{selectedBus.nameBn}</div>
                <div className="transit-hero-subtitle">
                  {selectedBus.nameEn} · {selectedBus.id}
                </div>
              </div>
              <div>
                <span
                  className={`transit-speed-badge ${selectedBus.speed > 0 ? "moving" : "stopped"}`}
                >
                  <Compass size={14} />
                  {selectedBus.speed > 0 ? `${selectedBus.speed} km/h` : "Parked at Campus"}
                </span>
              </div>
            </div>

            <div style={{ marginTop: 10, display: "flex", alignItems: "center", justifyContent: "space-between", fontSize: 12, color: "#cbd5e1" }}>
              <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                <Navigation size={14} color="#38bdf8" />
                <span>{selectedBus.routeName}</span>
              </div>
              {selectedBus.lastUpdated && (
                <div style={{ fontSize: 10, color: "#94a3b8" }}>
                  Updated: {selectedBus.lastUpdated}
                </div>
              )}
            </div>
          </div>

          {/* Dynamic ETA / Distance Box */}
          <div className="transit-eta-box">
            <div className="transit-eta-header">
              <div>
                <div style={{ fontSize: 11, fontWeight: 700, textTransform: "uppercase", letterSpacing: "0.5px", color: "#93c5fd" }}>
                  {isParked ? "Status" : "Estimated Arrival"}
                </div>
                <div className="transit-eta-big">
                  {isParked ? "Parked" : `~${etaMinutes} min`}
                </div>
              </div>
              <div style={{ textAlign: "right" }}>
                <div className="transit-eta-dist">
                  {(distanceToStationMeters / 1000).toFixed(2)} km
                </div>
                <div style={{ fontSize: 11, color: "#94a3b8" }}>
                  {isParked ? "Standby in campus" : "to chosen stoppage"}
                </div>
              </div>
            </div>

            {/* Target Stoppage Picker */}
            <div>
              <div className="transit-station-picker-label">Target Station / Your Stoppage</div>
              <select
                value={selectedStationId}
                onChange={(e) => setSelectedStationId(e.target.value)}
                className="transit-station-select"
              >
                {userLocation && <option value="user_gps">📍 Your Current GPS Location</option>}
                {STATIONS.map((st) => (
                  <option key={st.id} value={st.id}>
                    🚏 {st.name}
                  </option>
                ))}
              </select>
            </div>

            {/* Center Bus on Map */}
            <button
              onClick={() => centerOnBus(selectedBus)}
              className="transit-action-btn"
              style={{ width: "100%", justifyContent: "center", background: "#0284c7", color: "white", border: "none" }}
            >
              <Crosshair size={16} />
              <span>Focus {selectedBus.nameBn} on Map</span>
            </button>
          </div>

          {/* Stoppage Progress Timeline */}
          <div className="transit-card">
            <div style={{ fontSize: 12, fontWeight: 700, color: "#94a3b8", textTransform: "uppercase", letterSpacing: "0.5px", marginBottom: 12 }}>
              Route Corridor Headway
            </div>

            <div className="transit-timeline">
              {STATIONS.map((st, idx) => {
                const distToStop = calculateDistanceMeters(selectedBus.lat, selectedBus.lon, st.lat, st.lon);
                const isClosest = distToStop < 400;
                const isTarget = st.id === selectedStationId;

                return (
                  <div
                    key={st.id}
                    className={`transit-timeline-item ${isClosest ? "active" : ""}`}
                    onClick={() => setSelectedStationId(st.id)}
                    style={{ cursor: "pointer" }}
                  >
                    {idx < STATIONS.length - 1 && <div className="transit-timeline-line" />}
                    <div className="transit-timeline-dot" />
                    <div className="transit-timeline-content">
                      <div className="transit-timeline-title" style={{ color: isTarget ? "#38bdf8" : "#f1f5f9" }}>
                        {st.name} {isTarget ? "★" : ""}
                      </div>
                      <div className="transit-timeline-desc">
                        {isClosest
                          ? "Bus currently at / near this stop"
                          : `${(distToStop / 1000).toFixed(2)} km from bus`}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Driver Card */}
          <div className="transit-card">
            <div className="transit-driver-row">
              <div>
                <div style={{ fontSize: 13, fontWeight: 700, color: "#fff" }}>{selectedBus.driverName}</div>
                <div style={{ fontSize: 11, color: "#94a3b8" }}>Driver · {selectedBus.capacity}</div>
              </div>
              <a href={`tel:${selectedBus.driverPhone}`} className="transit-call-btn">
                <Phone size={14} />
                <span>Call Driver</span>
              </a>
            </div>
          </div>

          {/* Departure Alert Switch */}
          <div className="transit-card" style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <div style={{ padding: 8, borderRadius: 10, background: "rgba(56, 189, 248, 0.15)", color: "#38bdf8" }}>
                <Bell size={18} />
              </div>
              <div>
                <div style={{ fontSize: 13, fontWeight: 700, color: "#fff" }}>Campus Departure Alert</div>
                <div style={{ fontSize: 11, color: "#94a3b8" }}>Notify when bus leaves Santosh campus</div>
              </div>
            </div>
            <input
              type="checkbox"
              checked={alertEnabled}
              onChange={(e) => setAlertEnabled(e.target.checked)}
              style={{ width: 18, height: 18, accentColor: "#0284c7", cursor: "pointer" }}
            />
          </div>

          {/* Active Fleet Selector Grid */}
          <div>
            <div style={{ fontSize: 11, fontWeight: 800, color: "#94a3b8", textTransform: "uppercase", letterSpacing: "0.5px", marginBottom: 10 }}>
              MBSTU Fleet Vehicles ({filteredFleet.length})
            </div>
            <div className="transit-fleet-grid">
              {filteredFleet.map((bus) => (
                <div
                  key={bus.id}
                  onClick={() => {
                    setSelectedBusId(bus.id);
                    centerOnBus(bus);
                  }}
                  className={`transit-fleet-card ${bus.id === selectedBusId ? "selected" : ""}`}
                >
                  <div>
                    <div className="transit-fleet-name">{bus.nameBn}</div>
                    <div className="transit-fleet-id">{bus.id}</div>
                  </div>
                  <div>
                    <span
                      style={{
                        fontSize: 10,
                        fontWeight: 700,
                        padding: "2px 6px",
                        borderRadius: 6,
                        background: bus.speed > 0 ? "rgba(16, 185, 129, 0.2)" : "rgba(148, 163, 184, 0.15)",
                        color: bus.speed > 0 ? "#34d399" : "#94a3b8"
                      }}
                    >
                      {bus.speed > 0 ? `${bus.speed} km/h` : "Parked"}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          </div>

        </div>
      </div>
    </div>
  );
}
