document.addEventListener('DOMContentLoaded', () => {
    const loginForm = document.getElementById('loginForm');
    const usernameInput = document.getElementById('username');
    const passwordInput = document.getElementById('password');
    const clearBtn = document.getElementById('clearBtn');
    const systemMessage = document.getElementById('systemMessage');

    loginForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        
        const username = usernameInput.value.trim();
        const password = passwordInput.value;

        if (username && password) {
            systemMessage.textContent = 'Authenticating... Please wait.';
            systemMessage.style.color = '#000';
            
            try {
                // Send credentials to backend
                const response = await fetch('/api/login', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify({ username, password })
                });

                const data = await response.json();

                if (response.ok && data.success) {
                    systemMessage.style.backgroundColor = '#D4EDDA';
                    systemMessage.style.borderColor = '#28A745';
                    systemMessage.style.color = '#155724';

                    if (data.requirePasswordChange) {
                        systemMessage.textContent = 'First Time Login Detected. Redirecting to password reset...';
                        // TODO: Implement password reset UI and logic
                        setTimeout(() => {
                            alert("This is where the 'Change Password' screen will appear!");
                            systemMessage.textContent = 'Please stand by...';
                        }, 1000);
                    } else {
                        systemMessage.textContent = 'Login Successful. Accessing StoreNET...';
                        setTimeout(() => {
                            window.location.href = '/dashboard';
                        }, 800);
                    }
                } else {
                    // Login failed
                    throw new Error(data.message || 'Invalid Credentials');
                }

            } catch (err) {
                systemMessage.textContent = err.message || 'Connection error. Please try again.';
                systemMessage.style.color = '#D8000C';
                systemMessage.style.backgroundColor = '#FFD2D2';
                systemMessage.style.borderColor = '#D8000C';
                passwordInput.value = '';
                passwordInput.focus();
            }
        }
    });

    clearBtn.addEventListener('click', () => {
        usernameInput.value = '';
        passwordInput.value = '';
        systemMessage.textContent = 'Please enter your credentials to access the system.';
        systemMessage.style.color = '#333';
        systemMessage.style.backgroundColor = '#E6F0F5';
        systemMessage.style.borderColor = '#5B8092';
        usernameInput.focus();
    });
});