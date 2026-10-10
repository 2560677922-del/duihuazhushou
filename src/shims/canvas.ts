// Canvas mock for browser environment
// nekoai-js imports canvas for Node.js image processing, but we only need the API client in browser

export default {
  createCanvas() {
    return {
      getContext() {
        return {
          fillRect() {},
          clearRect() {},
          getImageData() { return { data: [] }; },
          putImageData() {},
          createImageData() { return { data: [] }; },
          setTransform() {},
          drawImage() {},
          save() {},
          fillText() {},
          restore() {},
          beginPath() {},
          moveTo() {},
          lineTo() {},
          closePath() {},
          stroke() {},
          translate() {},
          scale() {},
          rotate() {},
          arc() {},
          fill() {},
          measureText() { return { width: 0 }; },
          transform() {},
          resetTransform() {},
          isPointInPath() { return false; },
          clip() {},
          rect() {},
          quadraticCurveTo() {},
          bezierCurveTo() {},
          createLinearGradient() { return { addColorStop() {} }; },
          createRadialGradient() { return { addColorStop() {} }; },
          createPattern() { return null; },
          setLineDash() {},
          getLineDash() { return []; },
        };
      },
      toBuffer() { return Buffer.from([]); },
      toDataURL() { return 'data:image/png;base64,'; },
      getImage() { return null; },
    };
  },
  loadImage() { return Promise.resolve(null); },
  loadImageFromBuffer() { return Promise.resolve(null); },
  loadImageFromURL() { return Promise.resolve(null); },
  ImageData: function() {},
  Canvas: function() {},
  CanvasRenderingContext2D: function() {},
};
